# Matthew's Sandbox

A ragdoll physics sandbox. Spawn dummies, crates, TNT, balloons and carts, then fling, tie, pin and blast them.

- `www/index.html` - the game inside the APK (works offline, uses the local `matter.min.js`)
- `docs/index.html` - the same game for GitHub Pages (loads Matter.js from a CDN)
- `.github/workflows/build-apk.yml` - builds the APK on every push to `main`
- `icons/` - app icon, `debug.keystore` - keeps the signing key the same so updates install over the old app

## Get the APK
Open **Releases → apk-latest** and download `MatthewsSandbox.apk`:
https://github.com/gnotesamsung7-hue/matthews-sandbox/releases/tag/apk-latest

When you change the game, update both `www/index.html` and `docs/index.html`
(in `www/` keep the line `<script src="matter.min.js">`).
