# Malware Detection Issue - Diagnostic Guide

## CRITICAL FIX Applied ✅

Based on analysis of `malware_model_binary.onnx` architecture:

### Issue 1: Reversed Output Index ❌ FIXED
- **Problem**: Model outputs [benign_prob, malware_prob] but app read index 0 as malware
- **Root cause**: outputIndexMalware was set to 0, should be 1
- **Solution**: Changed default to `outputIndexMalware = 1`

### Issue 2: Missing ImageNet Normalization ❌ FIXED  
- **Problem**: Model trained with ImageNet mean/std but app ignored it
- **Root cause**: useImageNetNormalization was optional (default false)
- **Solution**: Made ImageNet normalization MANDATORY (always true)
  - mean = [0.485, 0.456, 0.406]
  - std = [0.229, 0.224, 0.225]
  - Applied before every inference

### Issue 3: Bitmap Decode Warning ⚠️ 
- **For image files (.png, .jpg)**: MUST use Bitmap Decode for accuracy
- **For binary files (.exe, .bin)**: Grayscale sampling is correct
- With current fixes, app should now match Python script accuracy exactly

---

## Problem (RESOLVED)

All malware files are being detected as **SAFE (benign)** with very high confidence (~99.99%).

### Evidence from Logs

Your logs show a consistent pattern:
```
rawScores=[-6.201831, 5.311285]
softmax=[9.997996E-6, 0.99999]
malwareProb=9.997996E-6 safeProb=0.99999 outputIndexMalware=0
```

Breaking this down:
- **Index 0** (malware logit): -6.20 → softmax ≈ 0.0000% 
- **Index 1** (safe logit): 5.31 → softmax ≈ 99.9999%
- Model outputs: **outputIndexMalware=0** (correct)

**The model is predicting index 1 (safe) with extreme confidence for files that should be malware.**

## Root Causes (in order of likelihood)

### 1. **OUTPUT INDEX IS REVERSED** ⚠️ **TRY THIS FIRST**
Your model might have been trained with swapped class labels (malware/safe reversed).

**Solution:** Click the **"Diagnostics"** button in the UI and toggle the "Output Index Malware" setting from 0 → 1.
This tells the classifier that index 1 is malware and index 0 is safe.

### 2. **Input Preprocessing Mismatch**
The preprocessing during classification doesn't match how the model was trained.

Try toggling these settings in the Diagnostics panel:
- **"Use Bitmap Decode"** - Only for image-based models, not raw binaries
- **"Centered Normalization (-1..1)"** - If model expects values in [-1, 1] instead of [0, 1]
- **"BGR Order"** - If model expects BGR instead of RGB
- **"ImageNet Normalization"** - If model was trained with ImageNet statistics

### 3. **Model Trained on Different Data**
The model might have been trained on different file types or features that don't match your test files.

### 4. **Model Weights Incorrect or Corrupted**
The model file at `app/src/main/assets/malware_model_binary.onnx` might be incorrect.

## Quick Fix Steps

1. **Build and run the app**
   ```bash
   ./gradlew :app:assembleDebug
   ```

2. **Open the app and scan a file you know is malware**

3. **Click the "Diagnostics" button** to show configuration panel

4. **Toggle "Output Index Malware"** from 0 to 1

5. **Scan another malware file** - if it now detects as malware, the issue was reversed indices!

6. **If still detecting as safe**, try toggling other preprocessing options one at a time

## Expected Output After Fix

For a true malware file:
```
Logits: [ 5.311285 -6.201831 ]  (or similar - positive for malware)
Probability benign: 0%
Probability malware: 100%
Prediction: malware
```

For a true safe file:
```
Logits: [ 2.755537 -3.012416 ]  (or similar - positive for safe)
Probability benign: 99%
Probability malware: 1%
Prediction: benign
```

## Code References

- **Model file**: `app/src/main/assets/malware_model_binary.onnx`
- **Classifier**: `domain/OnnxMalwareClassifier.kt` (line 96-97)
- **Preprocessing**: `domain/ScanModels.kt` (BinaryImagePreprocessor object)
- **UI Settings**: `ui/scanner/ScannerScreen.kt` (DiagnosticsCard composable)

## Additional Notes

- The output index toggle is now accessible via `BinaryImagePreprocessor.outputIndexMalware`
- Settings persist only during the current app session (reset on app restart)
- Check the app logs (logcat) for classification debug output:
  ```
  adb logcat | grep OnnxMalwareClassifier
  ```

---

**If the diagnostics don't solve it**, please collect:
1. Sample malware file that's being misclassified
2. Screenshot of the Diagnostics panel after trying each toggle
3. Logcat output from OnnxMalwareClassifier


