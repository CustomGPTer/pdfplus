# PDF+

A free PDF reader, annotator and toolkit for Android. No adverts, no tracking, files stay on the phone.

- Package: `uk.jj.pdfplus`
- Works on Android 11 and later (64-bit ARM phones and tablets, including Galaxy S23 and S25)
- Licence: GNU Affero General Public License v3 (see `LICENSE`)

## What it does

**Library** – finds every PDF on the phone, recent list with thumbnails, folders, favourites, tags,
collections, a private folder, and search by file name or by the words inside documents.

**Reading** – continuous scroll, one page, or two-page spread; deep zoom that re-sharpens the visible area;
day, night, sepia, grey and custom colours remembered per document; outline, page thumbnails and bookmarks;
find in document; reading view (reflowed text); read aloud with the phone's voices; tabs for several
documents; layers on/off.

**Annotate** – highlight, underline, strikeout, squiggle, pen, marker, eraser, text box, callout, sticky note,
rectangle, ellipse, cloud, line, arrow, stamps, pictures; move, resize, recolour, delete; undo and redo;
a list of all notes that can be exported. Notes are written into the PDF in the standard way.

**Fill and sign** – fill in form fields, tick boxes and lists; drawn, typed or photographed signatures;
tick, cross and date marks; certificate-based digital signatures from a digital ID.

**Edit** – replace a line of text, lift, move or delete a picture, add text and pictures, create form fields.

**Pages** – reorder, rotate, delete, extract, crop, insert blank pages or pages from another PDF.

**Measure** – set a drawing scale (preset or from a known length), then measure lengths, paths and areas.

**Protect** – true redaction, open/add/remove passwords and permissions, app lock, private folder.

**Tools** – scan to PDF, OCR, merge, split, compress, flatten, page numbers, header and footer, watermark,
pictures to PDF, other files to PDF, PDF to Word, Excel, pictures and plain text.

**Google Drive (optional)** – open PDFs from Drive folders you choose; sync reading positions, bookmarks
and a Synced folder between devices.

**AI (optional, off by default)** – summaries and questions using the user's own key with OpenAI,
Anthropic or Google Gemini.

## Building

Requirements: JDK 17 or later and the Android SDK (platform 36, build-tools 36.0.0).

    ./gradlew :app:assembleRelease

Release builds are signed with the key named in `signing.properties` (not part of the public source).
Without that file, build the debug variant instead: `./gradlew :app:assembleDebug`.

## Built on

- [MuPDF](https://mupdf.com) by Artifex Software – AGPL v3
- [Bouncy Castle](https://www.bouncycastle.org) – MIT licence
- Google ML Kit (text recognition, document scanner) and Google Play services
- Android Jetpack and Jetpack Compose – Apache 2.0

Because PDF+ includes MuPDF under the AGPL, the complete source of PDF+ is published under the same licence.

## Website

The public site in `docs/` is served by GitHub Pages. Each release attaches the APK as `PDFPlus.apk`,
which is what the site's download button and the in-app new-version check look for.
