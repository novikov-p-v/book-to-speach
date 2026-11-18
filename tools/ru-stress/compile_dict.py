"""Compile an espeak-ng dictionary using the espeak-ng library bundled in the piper-tts wheel.

usage: compile_dict.py <espeak-ng-data dir (ru_dict is written here)> <dictsource dir> <lang>

The bundled library reproduces the ru_dict shipped with sherpa-onnx voices byte-for-byte
when given the same sources (verified), so its output is compatible with the app's engine.
"""
import ctypes, ctypes.util, glob, os, sys
import piper

lib = ctypes.CDLL(glob.glob(os.path.join(os.path.dirname(piper.__file__), "espeakbridge*.so"))[0])
libc = ctypes.CDLL(ctypes.util.find_library("c"))
libc.fopen.restype = ctypes.c_void_p
data, src, lang = sys.argv[1], sys.argv[2].rstrip("/") + "/", sys.argv[3]
log = libc.fopen(os.path.join(src, "compile.log").encode(), b"w")
ctx = ctypes.c_void_p(0)
lib.espeak_ng_InitializePath(data.encode())
assert lib.espeak_ng_Initialize(ctypes.byref(ctx)) == 0, "espeak init failed"
lib.espeak_SetVoiceByName(lang.encode())  # the compiler needs a loaded voice
lib.espeak_ng_CompileDictionary.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p, ctypes.c_int, ctypes.c_void_p]
status = lib.espeak_ng_CompileDictionary(src.encode(), lang.encode(), log, 0, ctypes.byref(ctx))
libc.fflush(ctypes.c_void_p(log))
print("compile status", hex(status & 0xFFFFFFFF))
sys.exit(0 if status == 0 else 1)
