# Mobile Shield - Malware Scanner Android (MVP)

Aplikasi Mobile Shield adalah versi MVP yang sudah siap digunakan (Production-Ready). Aplikasi ini dirancang beroperasi layaknya perangkat lunak Antivirus modern, yang berfokus murni pada perlindungan perangkat pengguna dari ancaman malware secara lokal tanpa memerlukan koneksi internet.

Fitur utama:

- Manual File Scanner (Pemindai Sesuai Permintaan)
  - Pengguna dapat mengecek file mencurigakan apapun secara manual via Storage Access Framework (SAF).
  - Mesin AI akan membaca struktur biner file tersebut dan memprosesnya lewat tahapan: prapemrosesan → ONNX Inference → pascapemrosesan.
  - Hasil akan langsung ditampilkan di layar: ✅ Aman atau ⚠️ Malware beserta tingkat probabilitasnya (confidence).

- Active Shield (Real-time Background Malware Monitor)
  - Perlindungan otomatis yang memonitor aktivitas file baru secara real-time menggunakan `FileObserver`.
  - Default folder pemantauan: `Downloads` (atau folder masuk lainnya).
  - Jika ada file baru yang terunduh dan AI mendeteksinya sebagai ancaman (Malware), aplikasi akan langsung menembakkan Notifikasi Prioritas Tinggi agar pengguna tidak membuka file tersebut.
  - Sistem ini berjalan sangat ringan di latar belakang perangkat sebagai Foreground Service.

⚙️ Spesifikasi Teknis Engine

- Model Machine Learning berjalan sepenuhnya secara offline di dalam perangkat menggunakan file `app/src/main/assets/malware_model.onnx` (dan sidecar `malware_model.onnx.data`).
- Mesin memproses input dari byte file mentah lalu dikonversi menjadi bentuk matriks/gambar `1x3x224x224` piksel dengan format RGB.
- Catatan OS: Pada Android 10+, pemantauan folder dapat dibatasi oleh scoped storage. Gunakan folder yang memang bisa diakses oleh sistem aplikasi.
- Catatan OS: Untuk pengguna Android 13+, pastikan izin Notifikasi (Notification Permission) sudah diberikan agar sistem alarm peringatan dapat muncul.

🚀 Build Project

Pastikan Anda menggunakan Android Studio versi terbaru.

```bash
./gradlew :app:assembleDebug
```

📱 Alur Penggunaan Aplikasi

1. Buka aplikasi Mobile Shield.
2. Untuk Scan Manual: Tekan tombol "Pilih File", cari file (misal: .apk atau dokumen) yang baru saja Anda terima. Hasil keamanan akan langsung keluar.
3. Untuk Proteksi Otomatis: Pastikan path folder monitor sudah terisi (misal: Downloads), lalu tekan "Mulai Monitor". Anda bisa menutup aplikasi, dan pelindung akan tetap aktif berjaga di latar belakang.
4. Tekan "Hentikan Monitor" jika Anda ingin menonaktifkan pemantauan otomatis.

Jika Anda ingin menambahkan dokumentasi penggunaan lebih rinci, contoh screenshot, atau instruksi pengujian dataset internal, beri tahu saya agar README ini bisa saya kembangkan lagi.
