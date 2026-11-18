# Improved Russian stress for Piper voices

Piper voices pronounce text through espeak-ng, whose Russian dictionary misses or mis-stresses many word
forms (стука́йте instead of сту́кайте, Аркадьи́ч instead of Арка́дьич). This builds espeak's own `ru_dict` with
extra `word $N` entries (primary stress on vowel N) for every word where espeak disagrees with the
[RUAccent](https://huggingface.co/ruaccent/accentuator) dictionary (MIT). espeak's rules then work out vowel
reduction and consonants for the corrected stress. Homographs (за́мок/замо́к) are left to espeak.

Output: `app/src/main/assets/ru/ru_dict.gz` (the app keeps one shared espeak data folder with it).

## Results (sherpa-onnx 1.13.8 espeak data)
- 3,060,082 RUAccent words checked; espeak's stress differs on 936,413; 932,246 entries after filtering.
- Real prose (Anna Karenina ch. 1–3, Crime and Punishment ch. 1–2, 11k words): ~1 correction per 157 words,
  mostly names and rarer forms. Of 4,018 distinct words, only the 33 on the list change pronunciation.
- `ru_dict` grows from 8.1 MB to 18.3 MB (10.5 MB gzipped).

## Rebuild
```bash
cd tools/ru-stress && mkdir -p work && cd work
python3 -m venv venv && venv/bin/pip install sherpa-onnx==1.13.8 piper-tts numpy soundfile
# inputs: a Piper voice's espeak-ng-data (all sherpa-onnx models ship identical data),
#         RUAccent dictionaries (pinned revision), espeak-ng sources pinned by sherpa-onnx 1.13.8
curl -L https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-ru_RU-irina-medium.tar.bz2 | tar xj
R=https://huggingface.co/ruaccent/accentuator/resolve/b78ae5ea1e62beaf138bed1865cd8c3b0b5ca855/dictionary
mkdir -p ruaccent && for f in accents omographs yo_words yo_homographs; do curl -L -o ruaccent/$f.json.gz $R/$f.json.gz; done
curl -L -o espeak-src.zip https://github.com/csukuangfj/espeak-ng/archive/ed530aa113046142eb5115cf2fc9157854d0ffe1.zip
unzip -q espeak-src.zip && mv espeak-ng-ed530aa* espeak-src

E=espeak-src/dictsource
mkdir -p base/dsource && cp $E/ru_rules $E/ru_list $E/ru_emoji $E/extra/ru_listx base/dsource/
cp -r vits-piper-ru_RU-irina-medium/espeak-ng-data base/data
venv/bin/python -I ../compile_dict.py base/data base/dsource ru   # must reproduce the shipped ru_dict exactly
venv/bin/python -I ../find_wrong.py base/data ruaccent wrong.tsv  # ~4 h, resumable
cp -r base patched && venv/bin/python -I ../make_extra.py wrong.tsv ruaccent patched/dsource/ru_extra
venv/bin/python -I ../compile_dict.py patched/data patched/dsource ru
gzip -9 -c patched/data/ru_dict > ../../../app/src/main/assets/ru/ru_dict.gz
```
When regenerating, bump `EspeakData.VERSION` in the app so devices refresh their copy.

The compiler runs through the espeak-ng library bundled in the `piper-tts` wheel (`compile_dict.py`); with the
unmodified sources it reproduces the `ru_dict` shipped in sherpa-onnx voices byte-for-byte.
