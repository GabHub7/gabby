# Gabby (Android) v0.2.0 (agen)

Asisten AI pribadi dengan widget melayang: tanya jawab, scan layar ("ini apaan?"), fetch web, dan terminal (dengan persetujuan).
Panel pengaturan berupa web lokal. Semua data dan API key tersimpan di perangkat.

## Membangun APK (tanpa Android Studio)
1. Buat repo GitHub baru, unggah isi folder ini (termasuk `.github`).
2. Tab **Actions > Build APK** berjalan otomatis. Setelah hijau, unduh `app-debug.apk` dari **Releases** atau Artifacts.
3. Pasang di HP (izinkan sumber tidak dikenal).

## Pemakaian
1. Buka aplikasi > **Buka Pengaturan**. Panel web lokal terbuka di browser (127.0.0.1, dilindungi token).
   Pilih penyedia (Gemini, OpenAI, atau Claude), isi nama model dan API key, lalu Simpan.
2. Nyalakan **Gabby Aktif**, izinkan "Tampil di atas aplikasi lain". Ketuk maskot untuk membuka chat.
3. Perintah: `/tanya`, `/scan` (pindai layar lalu bertanya), `/files` (akses folder), `/sesi` (ganti/mulai sesi), `/ingatan`, `/tugas`, `/reset` (mulai baru dan hapus riwayat sesi ini).
4. Scan layar butuh layanan Aksesibilitas (Android 11+). Tombol "Aktifkan Aksesibilitas" menuntun langkahnya,
   termasuk "Izinkan pengaturan terbatas" untuk aplikasi di luar Play Store.

## Fitur lanjutan
- **Streaming:** jawaban Gemini, OpenAI, dan Claude muncul sedikit demi sedikit. Blok aksi tidak pernah tampil di gelembung chat.
- **Riwayat permanen:** chat tersimpan di penyimpanan privat aplikasi dan dimuat lagi saat Gabby aktif. Bisa dimatikan di Pengaturan;
  `/reset` menghapusnya. Gambar layar tidak pernah disimpan.
- **/files:** ketuk "Izinkan folder untuk Gabby" lalu pilih satu folder (jendela izin ala desain). Gabby hanya bisa melihat, membaca, dan menulis
  di dalam folder itu (jalur dengan `..` ditolak). Membaca bisa dibuat otomatis di Pengaturan; **menulis selalu meminta izin**.
  Izin bisa dicabut dari aplikasi atau dari panel web.
- **Termux (opsional, terminal Linux penuh):** pasang Termux, jalankan
  `echo "allow-external-apps=true" >> ~/.termux/termux.properties && termux-reload-settings`, lalu ketuk "Izinkan Termux".
  Setiap perintah tetap meminta izin. **Belum diuji pada perangkat** (butuh Termux asli), jadi kabari bila gagal.

## Mode agen (v0.2)
Gabby bekerja berulang: berpikir, memakai alat, membaca hasilnya, lanjut sampai selesai (batas langkah 3-30, default 12, di Pengaturan). Tombol **Stop** menghentikan di tengah jalan.
- **Alat:** shell (sandbox), Termux, fetch web, cari web (DuckDuckGo), file di folder izin, buka aplikasi/tautan, clipboard, tanggal-jam, ingatan, tugas terjadwal, rencana.
- **Persetujuan bertingkat:** tiap aksi meminta izin dengan tiga pilihan: *Sekali ini*, *Izinkan jenis ini 10 menit / 20 aksi*, atau *Tolak*.
  Untuk pekerjaan panjang Gabby bisa mengajukan **rencana** (langkah + alat yang dipakai); bila disetujui, alat itu otomatis selama 15 menit.
  Perintah berbahaya (rm, sudo, dd, mkfs, `| sh`, pm uninstall, settings put, force-stop, dll.) **selalu** bertanya, apa pun izinnya. Menulis file di luar rencana juga bertanya.
  Muatan besar (URL/kueri panjang) tidak ikut otomatis, supaya data tidak mudah bocor lewat alamat.
- **Ingatan jangka panjang:** catatan singkat tersimpan lokal (maks 200), dipakai di setiap sesi. Gabby bisa menyimpannya sendiri bila "Ingat otomatis" dinyalakan; menghapus selalu bertanya. Kelola di panel web.
- **Banyak sesi:** tiap percakapan punya sesi sendiri (maks 30, tersimpan lokal); lanjutkan, ganti, atau hapus lewat `/sesi` atau panel web.
- **Tugas terjadwal:** sekali / tiap N menit (min 15) / harian. Berjalan tanpa kamu, hanya dengan alat yang kamu izinkan saat membuat tugas (tanpa rencana, tanpa tugas bersarang, tanpa menghapus ingatan). Hasil muncul sebagai notifikasi dan di panel. Tugas yang terlewat (HP mati/Doze) dijalankan sekali saat Gabby hidup lagi. Android bisa menunda alarm: matikan optimasi baterai lewat tombol di aplikasi.

## Keamanan
- Perintah terminal, fetch web, cari web, dan menulis file meminta persetujuan. Otomatis hanya bila kamu memberi izin sesi/rencana (berbatas waktu) atau mengaktifkan baca-file otomatis; perintah berbahaya tidak pernah otomatis.
- Isi web, isi file, hasil perintah, dan isi layar diperlakukan sebagai data tidak tepercaya (instruksi di dalamnya diabaikan).
- API key dienkripsi (Android Keystore) dan tidak pernah dikirim balik ke panel web (hanya 4 karakter terakhir).
- Server pengaturan: hanya 127.0.0.1, token acak (cookie HttpOnly, SameSite=Strict), pemeriksaan Host dan Origin.
  Panel web tidak bisa memilih folder atau memberi persetujuan scan, hanya mencabutnya.
- Fetch web menolak alamat internal (localhost, 10.x, 192.168.x, 169.254.x, dll.).
- Layanan Aksesibilitas hanya dipakai untuk mengambil screenshot saat Scan ditekan.

## Batasan versi ini
- Terminal bawaan berjalan di sandbox aplikasi Android (perintah `sh` dasar). Untuk Linux penuh, pakai Termux.
- Belum ada: versi desktop dan membuat folder baru lewat `/files` (folder induk harus sudah ada).
- Nama model berubah dari waktu ke waktu: cek dokumentasi penyedia. Default Gemini dan Claude hanyalah contoh.

## Maskot dan animasi
Maskot memakai 10 gambar di `app/src/main/res/drawable-nodpi/`: `gabby_idle`, `gabby_hover`, `gabby_open`, `gabby_thinking`,
`gabby_scanning`, `gabby_working`, `gabby_success`, `gabby_error`, `gabby_notification`, `gabby_sleep` (.png, kanvas persegi, latar transparan).
Ganti file dengan nama yang sama untuk memakai gambarmu sendiri; yang tidak ada memakai gambar sederhana bawaan.

Gerak tiap state dihitung di `Motion.kt` (fungsi murni, ada tesnya):
- idle: melayang dan bernapas; hover: membesar dan bergoyang saat disentuh; chat terbuka: mengangguk pelan
- thinking: goyang miring; scanning: berdenyut dengan garis pindai; working: memantul seperti mengetik
- success: melompat dengan percikan bintang; error: bergetar lalu mereda; notifikasi: bel bergoyang bergelombang; sleep: napas lambat
- pop kecil dan crossfade 0,18 detik setiap pergantian state; miring searah geseran saat maskot digeser; mengecil sedikit saat ditekan
- mengikuti pengaturan sistem "hapus animasi" (animasi dimatikan) dan dirender ~30 fps untuk hemat baterai

## Tes
Logika inti diuji di JVM: adaptor tiga penyedia AI, streaming SSE sungguhan ke server lokal, parser aksi, kebijakan persetujuan, loop agen,
shell, fetch, penjaga jalur file, riwayat, server pengaturan, dan validasi pengaturan. Halaman pengaturan diuji di browser tiruan.
Seluruh kode Android lolos kompilasi. Belum pernah dijalankan di perangkat Android sungguhan (izin folder, Termux, scan layar, dan tugas terjadwal di latar belakang khususnya).
