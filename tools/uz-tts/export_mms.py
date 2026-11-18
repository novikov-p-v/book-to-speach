#!/usr/bin/env python3
"""Export a Meta MMS-TTS model (Hugging Face transformers format, safetensors) to a sherpa-onnx VITS model.

The ONNX interface and metadata match sherpa-onnx's own vits-mms-* models (x, x_length, noise_scale,
length_scale, noise_scale_w -> y; frontend=characters).

usage: export_mms.py <hf model dir> <out dir> <lang>
"""
import collections
import json
import os
import sys

import onnx
import torch
from torch import nn
from transformers import VitsModel

hf_dir, out_dir, lang = sys.argv[1:4]


class OnnxModel(nn.Module):
    """VitsModel.forward (transformers) inference path, with the scales as inputs."""

    def __init__(self, m: VitsModel):
        super().__init__()
        self.m = m

    def forward(self, x, x_length, noise_scale, length_scale, noise_scale_w):
        m = self.m
        mask_dtype = m.text_encoder.embed_tokens.weight.dtype
        positions = torch.arange(x.shape[1], dtype=x_length.dtype).unsqueeze(0)
        attention_mask = (positions < x_length.unsqueeze(1)).to(torch.int64)
        input_padding_mask = attention_mask.unsqueeze(-1).to(mask_dtype)
        enc = m.text_encoder(input_ids=x, padding_mask=input_padding_mask, attention_mask=attention_mask,
                             return_dict=True)
        hidden_states = enc.last_hidden_state.transpose(1, 2)
        input_padding_mask = input_padding_mask.transpose(1, 2)
        prior_means, prior_log_variances = enc.prior_means, enc.prior_log_variances

        if m.config.use_stochastic_duration_prediction:
            log_duration = m.duration_predictor(hidden_states, input_padding_mask, None, reverse=True,
                                                noise_scale=noise_scale_w)
        else:
            log_duration = m.duration_predictor(hidden_states, input_padding_mask, None)

        duration = torch.ceil(torch.exp(log_duration) * input_padding_mask * length_scale)
        predicted_lengths = torch.clamp_min(torch.sum(duration, [1, 2]), 1).long()
        indices = torch.arange(predicted_lengths.max(), dtype=predicted_lengths.dtype)
        output_padding_mask = (indices.unsqueeze(0) < predicted_lengths.unsqueeze(1)).unsqueeze(1).to(mask_dtype)

        attn_mask = torch.unsqueeze(input_padding_mask, 2) * torch.unsqueeze(output_padding_mask, -1)
        batch_size, _, output_length, input_length = attn_mask.shape
        cum_duration = torch.cumsum(duration, -1).view(batch_size * input_length, 1)
        indices = torch.arange(output_length, dtype=duration.dtype)
        valid = (indices.unsqueeze(0) < cum_duration).to(attn_mask.dtype).view(batch_size, input_length, output_length)
        padded = valid - nn.functional.pad(valid, [0, 0, 1, 0, 0, 0])[:, :-1]
        attn = padded.unsqueeze(1).transpose(2, 3) * attn_mask

        prior_means = torch.matmul(attn.squeeze(1), prior_means).transpose(1, 2)
        prior_log_variances = torch.matmul(attn.squeeze(1), prior_log_variances).transpose(1, 2)
        prior_latents = prior_means + torch.randn_like(prior_means) * torch.exp(prior_log_variances) * noise_scale
        latents = m.flow(prior_latents, output_padding_mask, None, reverse=True)
        return m.decoder(latents * output_padding_mask, None)  # (N, 1, samples)


@torch.no_grad()
def main():
    os.makedirs(out_dir, exist_ok=True)
    model = VitsModel.from_pretrained(hf_dir, use_safetensors=True).eval()
    vocab = json.load(open(os.path.join(hf_dir, "vocab.json"), encoding="utf-8"))
    tok_cfg = json.load(open(os.path.join(hf_dir, "tokenizer_config.json"), encoding="utf-8"))

    symbols = sorted(vocab, key=vocab.get)
    upper = [s.upper() for s in symbols]
    dup = {s for s, c in collections.Counter(upper).items() if c > 1}
    with open(os.path.join(out_dir, "tokens.txt"), "w", encoding="utf-8") as f:
        for s in symbols:
            f.write(f"{s} {vocab[s]}\n")
            if s.lower() != s.upper() and len(s.upper()) == 1 and s.upper() not in dup and s.upper() not in vocab:
                f.write(f"{s.upper()} {vocab[s]}\n")

    x = torch.randint(low=1, high=10, size=(1, 50), dtype=torch.int64)
    args = (x, torch.tensor([50], dtype=torch.int64), torch.tensor([1.0]), torch.tensor([1.0]), torch.tensor([1.0]))
    filename = os.path.join(out_dir, "model.onnx")
    torch.onnx.export(
        OnnxModel(model), args, filename, opset_version=13, dynamo=False,
        input_names=["x", "x_length", "noise_scale", "length_scale", "noise_scale_w"],
        output_names=["y"],
        dynamic_axes={"x": {0: "N", 1: "L"}, "x_length": {0: "N"}, "y": {0: "N", 2: "L"}},
    )
    meta = {
        "model_type": "vits",
        "comment": "mms",
        "url": "https://huggingface.co/facebook/mms-tts",
        "add_blank": int(tok_cfg.get("add_blank", True)),
        "language": lang,
        "frontend": "characters",
        "n_speakers": int(model.config.num_speakers if model.config.num_speakers > 1 else 0),
        "sample_rate": model.config.sampling_rate,
    }
    m = onnx.load(filename)
    for k, v in meta.items():
        p = m.metadata_props.add()
        p.key, p.value = k, str(v)
    onnx.save(m, filename)
    print("exported", filename, meta)


main()
