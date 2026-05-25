# Malware Scanner Android (ONNX)

Aplikasi ini menambahkan dua fitur utama:

1. **File Scanner & Dataset Tester**
   - Pilih file tunggal via SAF.
   - Pilih folder dataset via `OpenDocumentTree()`.
   - Proses klasifikasi berjalan di `Dispatchers.IO`.
   - Hasil menampilkan label `Aman` / `Malware`, confidence, dan ringkasan dataset.
   - Jika struktur folder dataset memiliki nama seperti `malware`, `benign`, `safe`, atau `clean`, aplikasi akan menghitung akurasi berdasarkan label folder.

2. **Real-time Background Malware Monitor**
   - Monitor folder path berbasis `FileObserver`.
   - Default folder: `Downloads`.
   - Jika file baru terdeteksi dan model memberi prediksi malware, aplikasi menampilkan notifikasi prioritas tinggi.
   - Service berjalan sebagai foreground service.

## Catatan penting

- Model yang dipakai ada di `app/src/main/assets/image_model_fixed.onnx`.
- Model ini dibaca sebagai input gambar `1x3x224x224` dari byte file mentah.
- Pada Android 10+ pemantauan folder langsung dapat dibatasi oleh scoped storage. Untuk hasil paling stabil, gunakan folder yang memang bisa diakses aplikasi.
- Jika notifikasi belum muncul di Android 13+, pastikan izin notifikasi sudah diberikan.

## Build

```bash
./gradlew :app:assembleDebug
```

## Alur penggunaan

1. Buka aplikasi.
2. Tekan **Pilih File** untuk scan file tunggal.
3. Tekan **Pilih Folder Dataset** lalu **Scan Dataset** untuk pengujian batch.
4. Isi path folder monitor, lalu tekan **Mulai Monitor**.
5. Tekan **Hentikan Monitor** untuk mematikan pemantauan.

