# Location Journal (Android, Kotlin)

A private location diary. It checks where you are every 15 minutes (4 times an hour), 24/7. It adds a
new entry only when you have actually moved, and records how long you stayed at each place. Entries are
grouped by date. You can pick a day or a date range from a calendar, and edit or add entries yourself,
including labels, purpose, notes and custom fields.

## Features

- **24/7 tracking**: a foreground service using Google's fused location provider, polling every *N* minutes
  (default 15). A watchdog alarm fetches a fix even when the phone is idle, and tracking restarts after a
  reboot or an app update.
- **Only records real moves**: a new entry is created only when you move farther than the distance
  threshold (default 150 m, configurable). Otherwise the current entry's end time is extended, so each
  entry shows *from → to* and the time spent there. GPS drift is handled by:
  - requiring the move to also exceed the fix's accuracy radius
  - ignoring fixes worse than a set accuracy
  - an optional *glitch filter* that removes a one-off jump that immediately comes back
- **Place names like Google Maps**: using OpenStreetMap, the app finds the named place you are
  *inside* (e.g. "Library, City University") or right next to (a café, shop or mall), plus the street
  address. It needs no API key. The **Suggest names** button lists nearby named places to pick from.
  Android's built-in geocoder is used as a fallback.
- **Saved places, mapped from addresses**: add "Home", "Office" or "University" by typing an address, by
  tapping the map, or from your current location, and set a radius. Any entry within that radius, plus
  up to 75 m of GPS slack, is matched to the place ("roughly" matching). It then gets the place's name,
  default labels and default purpose automatically, and optionally all your *past* entries there do too.
- **Timeline**: entries grouped under date headers, with a Day / Date range switch, a calendar picker,
  presets (last 7 or 30 days, this month, …), search, label filter, a map view with numbered pins and
  your path, and "moving / no data" gaps between stays. Stays that cross midnight are split correctly
  across both days.
- **Editable entries**: title, times, location (tap the map, search an address, or use your current
  location), linked saved place, what you were doing (with suggestions from your history), colored
  labels, notes, and **custom fields** (any key/value, e.g. *With*, *Mood*, *Cost*). You can add entries by
  hand for any day, and delete them.
- **Stats**: for any day or range, time per place, per label, per purpose and per kind of place.
- **Backup & export**: a full JSON backup and restore (merges by id), CSV export (everything, or the
  current day or range from the Timeline menu), and CSV import.
- Your data is stored only on the phone, in a Room/SQLite database.

## Get the APK (no Android Studio needed)

Every push builds the app on GitHub Actions:

1. Open the repo on GitHub, go to **Actions**, and open the latest **Build APK** run.
2. Download the **location-journal-apk** artifact (a zip) and unzip it. It contains two APKs:
   - `app-release.apk`: use this one (faster).
   - `app-debug.apk`: the same app with debugging enabled.
3. Install it on the phone:
   - Copy the APK to the phone and open it. Allow "install unknown apps" for your file manager when asked.
   - Or install it from Fedora over USB:
     ```bash
     sudo dnf install android-tools
     # On the phone: Settings → About → tap "Build number" 7× → Developer options → USB debugging
     adb devices
     adb install -r app-release.apk
     ```

All builds are signed with the same key (`keystore/app.keystore`), so a newer APK installs **over** the
old one and keeps your journal. Don't switch between the debug and release APKs: they have the same
package name, so uninstall first if you do, and export a backup before that.

## First run: make tracking reliable

Open **Settings** and turn on **Record my location**, then make every row green:

| Item | Why |
|---|---|
| Precise location | Needed for GPS fixes |
| Location "Allow all the time" | Lets tracking restart by itself after a reboot |
| Notifications | Shows the "recording" notification and warns you if Android stops tracking |
| Unrestricted battery use | Stops Doze/battery optimization from pausing tracking |

Some phone makers (Xiaomi, Huawei, Samsung, OnePlus, …) add their own app killers. If tracking stops,
also allow **Autostart** and set battery to **No restrictions** in the phone's app settings (see
<https://dontkillmyapp.com>).

## Build locally on Fedora

```bash
sudo dnf install java-17-openjdk-devel
# Install the Android SDK (command-line tools or Android Studio), then:
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew testDebugUnitTest assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

## How entries are recorded

```
every 15 min: get a location fix
  fix accuracy worse than limit ─────────────► ignore
  no current entry / >12 h since last fix ───► new entry
  inside the same saved place, or within
    max(threshold, fix accuracy) of the entry ► extend current entry's end time
  one-off jump that came back (glitch filter)► delete the jump, extend the previous entry
  otherwise ─────────────────────────────────► new entry (then look up its place name online)
```

The logic is in `tracking/VisitDecider.kt` and has unit tests in `app/src/test`.

## Project layout

```
app/src/main/java/app/locjournal/
  data/       Room entities (Visit, Tag, SavedPlace), DAOs, repository, DataStore settings
  tracking/   Foreground service, watchdog and boot receivers, VisitDecider (pure logic), VisitRecorder
  geo/        Distance math, saved-place matching, OSM/Geocoder place lookup, name picking
  io/         JSON backup and CSV import/export
  ui/         Jetpack Compose screens: timeline, entry editor, places, stats, settings
```

Map data and place names © OpenStreetMap contributors. Online lookups send the coordinates of each new
place to OpenStreetMap's Nominatim and Overpass servers. You can turn this off in Settings.
