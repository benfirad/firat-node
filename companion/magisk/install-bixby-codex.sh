#!/system/bin/sh
set -eu

# Build a device-local Magisk overlay instead of modifying /system. The module
# copies Samsung's own keylayout and changes only the dedicated Bixby scan code.
module=/data/adb/modules/daak-bixby-codex
source_keylayout=/system/usr/keylayout/Generic.kl
target_keylayout=$module/system/usr/keylayout/Generic.kl

if [ "${1:-}" = "--uninstall" ]; then
    if [ -d "$module" ]; then
        touch "$module/remove"
        echo "DAAK Bixby mapping will be removed at the next reboot."
    else
        echo "DAAK Bixby mapping is not installed."
    fi
    exit 0
fi

[ "$(id -u)" = 0 ] || { echo "Run as root." >&2; exit 1; }
[ -r "$source_keylayout" ] || { echo "Samsung Generic.kl not found." >&2; exit 1; }

matches=$(grep -Ec '^[[:space:]]*key[[:space:]]+703[[:space:]]+WINK([[:space:]]|$)' "$source_keylayout" || true)
[ "$matches" = 1 ] || {
    echo "Expected one Samsung Bixby key 703 WINK entry; found $matches." >&2
    exit 1
}

mkdir -p "$module/system/usr/keylayout"
cp -p "$source_keylayout" "$target_keylayout"
sed -i -E 's/^([[:space:]]*key[[:space:]]+703[[:space:]]+)WINK([[:space:]]*)$/\1F1\2/' "$target_keylayout"
grep -Eq '^[[:space:]]*key[[:space:]]+703[[:space:]]+F1([[:space:]]|$)' "$target_keylayout" || {
    echo "Patched keylayout validation failed." >&2
    exit 1
}

cat > "$module/module.prop" <<'EOF'
id=daak-bixby-codex
name=DAAK Bixby to Codex
version=1.0.0
versionCode=1
author=DAAK
description=Maps only the Galaxy S9/S9+ Bixby scan code to DAAK Node's projectless Codex action.
EOF

rm -f "$module/disable" "$module/remove"
touch "$module/update"
chmod 0755 "$module"
chmod 0644 "$module/module.prop" "$target_keylayout"

echo "DAAK Bixby mapping installed. Reboot to activate."
