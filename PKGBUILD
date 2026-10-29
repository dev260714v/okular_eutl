# SPDX-License-Identifier: GPL-2.0-or-later
# Experimental packaging adapted from the upstream Arch Linux Okular recipe.

pkgname=okular-eutl
_upstream=okular
pkgver=26.08.2
pkgrel=6
pkgdesc='Okular 26.08.2 with integrated local DSS EU trusted-list validation (experimental)'
arch=(x86_64)
url='https://apps.kde.org/okular/'
license=(GPL-2.0-or-later LGPL-2.0-or-later)
provides=("okular=$pkgver")
conflicts=(okular)
backup=('etc/okular-eutl/lotl-anchors.pem' 'etc/okular-eutl/oj-url.txt')
depends=('java-runtime>=17'
         discount
         djvulibre
         freetype2
         glibc
         karchive
         kbookmarks
         kcolorscheme
         kcompletion
         kconfig
         kconfigwidgets
         kcoreaddons
         kcrash
         ki18n
         kiconthemes
         kio
         kparts
         ktextwidgets
         kwallet
         kwidgetsaddons
         kwindowsystem
         kxmlgui
         libkexiv2
         libspectre
         libstdc++
         libtiff
         phonon-qt6
         poppler-qt6
         purpose
         qt6-base
         qt6-declarative
         qt6-speech
         qt6-svg
         threadweaver
         zlib)
makedepends=(cmake
             maven
             'java-environment>=17'
             python
             ebook-tools
             extra-cmake-modules
             kdegraphics-mobipocket
             kdoctools)
optdepends=('ebook-tools: mobi and epub support'
            'kdegraphics-mobipocket: mobi support'
            'unarchiver: Comic Book Archive support (alternative)'
            'unrar: Comic Book Archive support')
groups=(kde-applications
        kde-graphics)
source=(https://download.kde.org/stable/release-service/$pkgver/src/$_upstream-$pkgver.tar.xz{,.sig}
        okular-eutl.patch
        engine-source.tar
        okular-eutl-engine)
sha256sums=('f9b39abcb7c6c3ace5d7acf669fa7ed47cf3aa7b932a8b6abe2abbd8075fc9f5'
            'SKIP'
            'bae387a83f60e7251f28955dc81282e65a503436f6d479c928de2a1f95257299'
            '52509a929e25980ecd5af1129409455d987a6decf68303a04443e747fe079607'
            '49ea759a03c74a4fff039dc2a32b881d036b1b784f70439dbe30c5dd49af790a')
validpgpkeys=(CA262C6C83DE4D2FB28A332A3A6A4DB839EAA6D7  # Albert Astals Cid <aacid@kde.org>
              F23275E4BF10AFC1DF6914A6DBD2CE893E2D1C87  # Christoph Feck <cfeck@kde.org>
              D81C0CB38EB725EF6691C385BB463350D6EF31EF) # Heiko Becker <heiko.becker@kde.org>
options=(!zipman)

prepare() {
  cd "$_upstream-$pkgver"
  patch -Np1 -i "$srcdir/okular-eutl.patch"
}

build() {
  # A JRE-only Java 8 default must not select Maven's compiler/runtime.
  local eutl_jdk eutl_major
  for eutl_jdk in "${JAVA_HOME:-}" /usr/lib/jvm/java-*; do
    [[ -x "$eutl_jdk/bin/javac" ]] || continue
    eutl_major=$("$eutl_jdk/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p')
    [[ "$eutl_major" =~ ^[0-9]+$ ]] && (( eutl_major >= 17 )) || continue
    export JAVA_HOME="$eutl_jdk"
    break
  done

  python "$srcdir/tests/verify-trust.py"
  mvn -B -ntp -f "$srcdir/backend/pom.xml" package
  cmake -B build -S $_upstream-$pkgver \
    -DCMAKE_INSTALL_PREFIX=/usr \
    -DCMAKE_BUILD_TYPE=Release \
    -DBUILD_TESTING=OFF
  cmake --build build
}

package() {
  DESTDIR="$pkgdir" cmake --install build
  install -Dm644 "$srcdir/backend/target/okular-eutl-0.1.4.jar" "$pkgdir/usr/share/okular-eutl/engine.jar"
  install -Dm755 "$srcdir/okular-eutl-engine" "$pkgdir/usr/bin/okular-eutl-engine"
  install -Dm644 "$srcdir/trust/lotl-anchors.pem" "$pkgdir/etc/okular-eutl/lotl-anchors.pem"
  install -Dm644 "$srcdir/trust/oj-url.txt" "$pkgdir/etc/okular-eutl/oj-url.txt"
  install -Dm644 "$srcdir/trust/allowed-sha256.txt" "$pkgdir/usr/share/okular-eutl/allowed-sha256.txt"
}
