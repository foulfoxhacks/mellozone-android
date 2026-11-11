# Google Play submission sheet: Mello Zone

Everything below is ready to paste into Play Console. Items marked **You** need
your decision or your account.

## App identity

| Field | Value |
| --- | --- |
| Package name | `com.foulfoxhacks.mellozone` (permanent once uploaded) |
| App name | Mello Zone |
| Version | 1.0.0 (version code 1) |
| Default language | English (United States) |
| App or game | App |
| Category | Social |
| Free or paid | Free |
| Contains ads | No |
| Contact email | **You** (a public support address, e.g. the community contact on mellozone.site/contact) |
| Website | https://mellozone.site |
| Privacy policy URL | https://mellozone.site/privacy |

## Short description (80 characters max)

```
The 18+ SFW furry community for Discord, VRChat and Telegram. Read it offline.
```

## Full description (4000 characters max)

```
Mello Zone is an 18+ SFW furry community for Discord, VRChat and Telegram: a
safer, low-drama home for furries, creators, gamers, VRChat explorers and
friends. Different lives. Different stories. One community.

This is the official Mello Zone app. It brings everything on mellozone.site to
your phone as a fast, native Android app.

WHAT YOU CAN DO
• Find out how to join, who the community is for, and which official platform
  fits what you want to do
• Read the Mello Zone Journal: original furry community guides, VRChat
  reporting, creator spotlights and convention coverage
• Review the Safety Center, age-verification guide, reporting and appeal paths
  before you share anything
• Search every page and article instantly, even with no signal
• Save pages for later and download the whole site for offline reading
• Share any page, or open it in your browser

BUILT TO BE FAST
• Native Android screens, not a web page in a wrapper
• A Rust engine handles networking, content and search off the main thread, so
  scrolling stays smooth
• Pages open instantly from the copy on your device and refresh quietly in the
  background

RESPECTS YOUR DATA
• No account, no ads, no analytics, no tracking
• Data saver mode holds back images on mobile data
• Adjustable text size, pure-black mode, and full TalkBack support

Mello Zone is for adults aged 18 and over. Shared community spaces are SFW.
Partner communities and third-party platforms such as Discord, VRChat and
Telegram are independent and have their own rules and privacy practices.
```

## Graphics (in `store/graphics/`)

| Asset | File | Notes |
| --- | --- | --- |
| App icon, 512 x 512 | `play-icon-512.png` | Logo on the brand background |
| Feature graphic, 1024 x 500 | `feature-graphic-1024x500.png` | Cropped from the site's social image |
| Phone screenshots (2 to 8) | `screenshots/` | Captured from the release build |

**You:** the logo is the site's existing mark, which resembles the helmet of
the musician Marshmello. Google Play rejects apps for trademark and
impersonation concerns without warning. If you do not hold rights to that
likeness, change the icon before submitting (replace
`app/src/main/res/drawable-*/ic_launcher_foreground.png` and the two store
graphics).

## Content rating questionnaire (IARC)

Answer for what the app itself shows: community information and articles.

- Category: Reference, News or Educational (or Social, if you prefer; see below)
- Violence, sexuality, profanity, drugs, gambling: None in the app
- Users can interact or exchange content inside the app: **No** (the app has no
  chat, comments or uploads; it links out to Discord, VRChat and Telegram)
- Shares the user's location: No
- Allows purchases: No

## Target audience and content

- Target age group: **18 and over** only
- Appeals to children: No

## Data safety form

- Does the app collect or share any required user data types? **No**
- Is all user data encrypted in transit? Yes (HTTPS only; cleartext is disabled)
- Can users request data deletion? Not applicable (the app stores no account data)

What the app actually does: it downloads public pages, the article feed and
images from mellozone.site (and article photos from their original hosts) over
HTTPS. Saved pages, settings and bookmarks stay on the device. Forms on the
site (contact, report, appeal, applications) open in the user's browser; the
app never sees what is typed there.

## App access, ads, and declarations

- App access: All functionality is available without special access
- Ads: No
- Government app / financial features / health features: No
- News app: **You** decide. The Journal publishes original reporting; if you
  declare the app as a news app, Play asks for publisher contact details.

## Release checklist

1. Play Console → Create app → use the identity table above.
2. Enrol in **Play App Signing** (default). Upload `MelloZone-1.0.0-release.aab`.
3. Paste the listing text, upload the graphics and screenshots.
4. Complete content rating, target audience, data safety and privacy policy.
5. New personal developer accounts must run a closed test with at least 12
   testers for 14 days before production access is granted.
6. After the first upload, copy the **App signing key certificate SHA-256**
   from Play Console → Test and release → App integrity into
   `store/assetlinks.json`, then publish that file at
   `https://mellozone.site/.well-known/assetlinks.json` so mellozone.site links
   open directly in the app.
