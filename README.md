# MetaSearch

Pencari agregator: satu query dikirim ke banyak sumber lewat adapter, hasilnya digabung jadi grid thumbnail yang link-nya ke situs asal. Thumbnail di-blur sampai diketuk sekali; ketukan kedua membuka link.

## Fitur

- Banyak sumber paralel lewat adapter, hasil digabung selang-seling dan duplikat dibuang
- Filter konten yang menyangkut anak di bawah umur (pada query dan judul hasil)
- Thumbnail di-blur sampai diketuk
- Infinite scroll: halaman berikutnya dimuat otomatis saat mendekati akhir daftar
- Riwayat pencarian lokal (DataStore): maksimal 30 item, bisa dihapus satu per satu atau semuanya. Query yang diblokir filter tidak disimpan, dan `allowBackup` dimatikan supaya riwayat tidak ikut backup cloud.

## Build lewat GitHub Actions

1. Buat repo baru di GitHub, lalu dari folder proyek ini:

```bash
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/USERNAME/REPO.git
git push -u origin main
```

2. Buka tab **Actions** di repo. Workflow **Build APK** jalan otomatis tiap push ke `main`. Bisa juga dijalankan manual lewat **Run workflow**.
3. Setelah selesai (sekitar 3-6 menit untuk build pertama), buka run-nya, scroll ke **Artifacts**, unduh **MetaSearch-debug-apk**, ekstrak zip-nya, lalu pasang `app-debug.apk` di HP.

Workflow ini memakai Gradle 8.9 langsung dari action `setup-gradle`, jadi nggak perlu `gradlew` atau `gradle-wrapper.jar` di repo.

APK yang dihasilkan adalah debug build yang sudah otomatis ditandatangani dengan debug key. Itu cukup untuk dipasang sendiri.

## Isi sumber pertama

Sumber pertama (Eporner Webmaster API v2) sudah terisi di `app/src/main/java/com/example/metasearch/MainActivity.kt`. Baca syarat penggunaannya di situs mereka sebelum dipakai rutin. Untuk sumber lain, tambahkan `JsonFeedAdapter(...)` dengan endpoint dan nama field dari dokumentasi resmi situsnya:

- `urlTemplate`: URL endpoint, pakai `{query}` dan `{page}`
- `listPath`: path ke array hasil di JSON (boleh nested, mis. `data.items`)
- `titleKey`, `thumbKey`, `linkKey`: nama field di tiap item
- `tagsKey` (opsional): nama field kata kunci/tag. Isinya ikut disaring oleh `ContentFilter`, jadi isi kalau sumbernya menyediakan tag

Infinite scroll memanggil adapter dengan `page` = 2, 3, dst. Kalau sumbernya memakai offset atau cursor, bukan nomor halaman, sesuaikan `urlTemplate` atau bikin adapter sendiri. Pemuatan berhenti kalau halaman baru tidak membawa hasil baru.

Kalau formatnya bukan JSON, bikin class baru yang implement `SearchAdapter`.

## Struktur

| File | Fungsi |
|---|---|
| `SearchResult.kt` | Model hasil: judul, thumbnail, url, sumber |
| `SearchAdapter.kt` | Interface satu sumber |
| `JsonFeedAdapter.kt` | Adapter generik untuk feed JSON |
| `ContentFilter.kt` | Filter konten yang menyangkut anak di bawah umur |
| `SearchRepository.kt` | Jalankan semua adapter paralel, gabung, hapus duplikat |
| `SearchHistory.kt` | Riwayat pencarian lokal (DataStore) |
| `SearchViewModel.kt` | State UI, pagination, riwayat |
| `MainActivity.kt` | Daftar sumber, kolom cari, riwayat, grid dengan blur |

## Buka di Android Studio (opsional)

File > Open, pilih folder proyek ini. Kalau diminta Gradle, pilih versi 8.9.

## Langkah berikutnya

- Header khusus untuk thumbnail kalau ada sumber yang blokir hotlink.
- Daftar sumber yang bisa diatur dari dalam aplikasi.
- Kunci aplikasi dengan biometrik atau PIN.
