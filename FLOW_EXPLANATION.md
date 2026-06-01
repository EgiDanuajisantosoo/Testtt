# Alur Pemrosesan File → Klasifikasi Malware

## Pipeline Lengkap

```
┌─────────────────────────────────────────────────────────────┐
│ 1. USER MEMILIH FILE (via file picker)                      │
│    └─ File dapat berupa: .exe, .bin, .png, .jpg, dll        │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 2. BACA FILE SEBAGAI BYTE ARRAY (Raw Bytes)                 │
│    └─ `readBytes(uri)` di ScannerRepository.kt              │
│    └─ Contoh: byte[] = [0x4D, 0x5A, 0x90, 0x00, ...]       │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 3. CONVERT BYTES → GRAYSCALE (1D array → 2D matrix)         │
│    └─ `BinaryImagePreprocessor.toTensor(bytes, 224, 224)`   │
│    └─ File ukuran N bytes → disampling/padding ke 224×224   │
│                                                              │
│    Proses:                                                   │
│    - Jika N < (224×224): copy bytes, isi sisanya 0 (padding)│
│    - Jika N > (224×224): sample uniform (ambil setiap Nth)   │
│    - Setiap byte (0-255) → dinormalisir ke float (0-1)       │
│    └─ Output: FloatArray [224×224] grayscale values 0.0..1.0│
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 4. GRAYSCALE → TENSOR RGB (3-channel)                        │
│    └─ Replicate grayscale ke 3 channel (R, G, B)            │
│    └─ Output shape: 1 × 3 × 224 × 224 (NCHW format)         │
│                                                              │
│    ✅ MANDATORY Preprocessing:                               │
│    ✓ ImageNet Normalization (REQUIRED):                     │
│      └─ subtract mean = [0.485, 0.456, 0.406] per channel  │
│      └─ divide by std = [0.229, 0.224, 0.225] per channel  │
│      └─ Model accuracy depends on this!                     │
│                                                              │
│    Optional Preprocessing:                                   │
│    ○ Centered Normalization: [0,1] → [-1,1] (disabled)     │
│    ○ BGR Order: swap channel order (disabled)               │
│    ○ Bitmap Decode: use for .png/.jpg files only            │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 5. SEND TENSOR → ONNX MODEL (ML Inference)                   │
│    └─ Model: `malware_model_binary.onnx`                     │
│    └─ Input: FloatBuffer dengan shape [1, 3, 224, 224]      │
│    └─ Di: OnnxMalwareClassifier.classify()                  │
│                                                              │
│    Model Processing:                                         │
│    - Neural network layer 1 → layer 2 → ... → output        │
│    - Output: [logit_0, logit_1] (raw scores)                │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 6. POST-PROCESS OUTPUT → PROBABILITIES                       │
│    └─ Apply softmax: [logit] → [probability]                 │
│    └─ softmax([−6.20, 5.31]) → [9.99e-6, 0.99999]          │
│                                                              │
│    Model class layout (malware_model_binary.onnx):           │
│    - Index 0 = benign (SAFE)  [probability ≈ 0% untuk      │
│    - Index 1 = malware (DANGEROUS) [probability ≈ 100%]     │
│                                                              │
│    Output mapping (outputIndexMalware = 1):                  │
│    - malwareProbability = probability[1]                    │
│    - safeProbability = probability[0]                       │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 7. KEPUTUSAN KLASIFIKASI (Threshold = 0.5)                  │
│    └─ IF malwareProbability >= 0.5:                          │
│       └─ Prediksi: MALWARE (Merah 🔴)                        │
│    └─ ELSE:                                                  │
│       └─ Prediksi: SAFE (Hijau 🟢)                           │
└────────────────────┬────────────────────────────────────────┘
                     │
                     ↓
┌─────────────────────────────────────────────────────────────┐
│ 8. TAMPILKAN HASIL DI UI                                     │
│    ├─ Filename                                               │
│    ├─ Logits: [ -6.20 5.31 ]                                │
│    ├─ Probability benign: 0%                                │
│    ├─ Probability malware: 99%                              │
│    ├─ Prediction: malware / benign                          │
│    └─ Confidence score                                      │
└─────────────────────────────────────────────────────────────┘
```

---

## Contoh Konkret: Scan File `malware.exe`

### Step 1: Baca File
```
File: malware.exe (500 KB)
↓
Bytes: [4D, 5A, 90, 00, 03, 00, 00, 00, ...]  (500,000 bytes)
```

### Step 2: Convert ke Grayscale Tensor
```
Input bytes: 500,000 bytes
Target size: 224 × 224 = 50,176 pixels

Sampling formula:
for pixel_index in 0..50175:
    byte_index = (pixel_index * 500000) / 50176  ← uniform sampling
    grayscale[pixel_index] = bytes[byte_index] / 255

Contoh:
  Pixel 0: ambil byte 0 = 0x4D = 77  → normalized: 77/255 = 0.302
  Pixel 1: ambil byte 10 = 0x00 = 0  → normalized: 0/255 = 0.000
  ...
  Pixel 50175: ambil byte 499999 = 0xAB = 171 → normalized: 171/255 = 0.671

Output: FloatArray[50176] = [0.302, 0.000, ..., 0.671]
```

### Step 3: Replicate → RGB Tensor
```
Channel R: [0.302, 0.000, ..., 0.671]
Channel G: [0.302, 0.000, ..., 0.671]  ← sama (grayscale)
Channel B: [0.302, 0.000, ..., 0.671]

Shape akhir: [1, 3, 224, 224]
  [1]    = batch size (proses 1 file)
  [3]    = 3 channel RGB
  [224]  = tinggi pixel
  [224]  = lebar pixel
```

### Step 4: Inference ONNX Model
```
Input tensor [1, 3, 224, 224] = 150,528 float values
↓
Model neural network processing...
↓
Output logits: [-6.201831, 5.311285]
           (index 0)  (index 1)
```

### Step 5: Softmax
```
rawScores = [-6.20, 5.31]

Softmax formula:
exp(−6.20) = 1.99e-3
exp(5.31) = 202.0
total = 203.99e-3

prob[0] = 1.99e-3 / 203.99e-3 = 9.99e-6 ≈ 0.00099%
prob[1] = 202.0 / 203.99e-3 = 0.99999 ≈ 99.999%
```

### Step 6: Klasifikasi

**UPDATED: Output Index adalah 1, ImageNet Normalization MANDATORY**

```
outputIndexMalware = 1:  ← ✅ CORRECT (setelah fix)
  malwareProbability = prob[1] = 0.99999
  safeProbability = prob[0] = 9.99e-6
  
  Threshold = 0.5
  IF 0.99999 >= 0.5 → TRUE
  Result: MALWARE ✓ (benar!)
  
ImageNet Normalization APPLIED:
  ✓ Setiap grayscale value sudah dinormalisasi dengan:
    - mean = [0.485, 0.456, 0.406]
    - std = [0.229, 0.224, 0.225]
  ✓ Ini WAJIB untuk accuracy yang sama dengan Python script
```

---

## File-File Penting dalam Pipeline

| File | Lokasi | Fungsi |
|------|--------|--------|
| **readBytes()** | `ScannerRepository.kt` | Baca bytes dari URI |
| **toTensor()** | `ScanModels.kt` (BinaryImagePreprocessor) | Bytes → Grayscale → RGB Tensor |
| **classify()** | `OnnxMalwareClassifier.kt` | Send ke model, ambil output |
| **softmax()** | `OnnxMalwareClassifier.kt` | Logits → Probabilities |
| **finalLabel()** | `ScanModels.kt` | Probability → Klasifikasi SAFE/MALWARE |

---

## Variasi dengan Preprocessing

Jika file input adalah **GAMBAR** (`.png`, `.jpg`):

```
Alternatif dengan useBitmapDecodeForImages = true:

File: image.png (100 KB)
↓
Bitmap Decode: PNG → RGB pixel array (native image decode)
↓
Resize ke 224×224 (jika beda ukuran)
↓
Channel extraction: ambil R, G, B secara native (bukan grayscale)
↓
Normalisasi & posting → Model
↓
Output: lebih akurat karena preserve struktur gambar real
```

---

## Mengapa Perlu Preprocessing?

1. **Tensor Standardization**: Model hanya terima input ukuran tetap (1, 3, 224, 224)
2. **Binary → Grayscale**: File binary (.exe, .bin) tidak punya warna → treat as grayscale
3. **ImageNet Normalization (CRITICAL)**: 
   - Model dilatih dengan normalisasi khusus
   - Tanpa ini, model melihat input dengan scale yang salah → akurasi buruk
   - mean = [0.485, 0.456, 0.406]
   - std = [0.229, 0.224, 0.225]
4. **Channel Replication**: Kalau model expect 3 channel, grayscale harus dijadikan RGB

---

## CRITICAL: Perbedaan Python vs Android

| Aspek | Python (test.py) | Android (sebelum fix) | Android (sesudah fix) |
|-------|------------------|----------------------|----------------------|
| Output Index | 1 | ❌ 0 | ✅ 1 |
| ImageNet Norm | ✅ Required | ❌ Optional | ✅ Required |
| mean/std | [0.485, 0.456, 0.406] / [0.229, 0.224, 0.225] | None or ignored | ✅ Always applied |
| Result | Accurate | All SAFE (wrong) | ✅ Accurate |

---

## Testing Flow

```
┌─ Test 1: Binary file (malware.bin)
│  └─ Grayscale sampling path
│  └─ Cek output classification
│
├─ Test 2: Executable (malware.exe)
│  └─ Same grayscale path
│  └─ Size besar → uniform sampling
│  
└─ Test 3: Image file (malware.png)
   └─ IF useBitmapDecodeForImages = true:
   │  └─ Bitmap decode path
   │  └─ RGB native format
   └─ IF useBitmapDecodeForImages = false:
      └─ Grayscale sampling path (treat as binary)
```

---

## Debugging: Lihat Setiap Step

```kotlin
// Di logcat (SETELAH FIX):
D/OnnxMalwareClassifier: rawScores=[-6.201831, 5.311285]
D/OnnxMalwareClassifier: softmax=[9.997996E-6, 0.99999]
D/OnnxMalwareClassifier: malwareProb=0.99999 safeProb=9.997996E-6 outputIndexMalware=1
                         ↑ CORRECT! (index 1 = malware)    ↑ Now 1
                         
// Preprocessing applied:
// ImageNet Normalization MANDATORY:
// - mean subtracted per channel: [0.485, 0.456, 0.406]
// - std divided per channel: [0.229, 0.224, 0.225]
// - ini membuat output cocok dengan Python script
```

---

**Kesimpulan**: Ya, setiap file:
1. ✓ Baca sebagai **bytes mentah**
2. ✓ Convert ke **grayscale image** (224×224)
3. ✓ Menjadi **tensor RGB** (1×3×224×224)
4. ✓ Dikirim ke **ML model** untuk inference
5. ✓ Dapat output **probability** untuk SAFE/MALWARE






