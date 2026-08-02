# Masamune auth — subscription login by vendor redirect

> "with masamune. you included no login for subscriptions. I don't use api."
> "oauth need to redirect to the login for the vendor then back to the app after."
> — user, 2026-08-02

## The defect

`ai/AiService.kt` had `isUsable = apiKey.isNotBlank()`. `ai/Providers.kt` sent
`Authorization: Bearer <apiKey>` / `x-api-key`. `ai/ProviderStore.kt` persisted a
single `api_key`. No account login existed anywhere.

## The design — and it is not negotiable

**Authorization Code + PKCE, in a Custom Tab, redirecting back to the app.**

The user taps *Sign in*, Masamune opens **the vendor's own login page**, the user
signs in there with their subscription account, and the vendor redirects back
into Masamune with an authorization code, which Masamune exchanges for tokens.

That is the whole flow. Three things follow from it, and each is a hard rule:

### 1. A Custom Tab, never an in-app WebView

`androidx.browser.customtabs.CustomTabsIntent`, not a `WebView`.

- The user must see the **real vendor URL and its TLS padlock**. A login form
  rendered inside our app is indistinguishable from a phishing page, and asking
  someone to type vendor credentials into our process is exactly the thing this
  suite exists to argue against.
- A Custom Tab shares the browser's cookie jar, so an already-signed-in
  subscription session is picked up with no re-entry. That is the actual point
  of "subscription login" rather than API keys.
- Google and others **actively block** OAuth in embedded WebViews
  (`disallowed_useragent`). A WebView implementation does not merely smell wrong,
  it fails.

### 2. PKCE, and no client secret

Masamune is a public client — anything shipped in the APK is readable. So:
`code_challenge = BASE64URL(SHA256(code_verifier))`, `code_challenge_method=S256`,
verifier held only in memory for the duration of the flow. Never the implicit
flow, never a secret in the binary.

Also send `state`, and **verify it on return**. Without that check the redirect
handler will accept a code injected by any app that can fire the intent.

### 3. The redirect comes back through a manifest intent-filter

```xml
<activity android:name=".auth.OAuthRedirectActivity"
          android:exported="true"
          android:launchMode="singleTask">
  <intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    <!-- https App Link where the vendor permits; custom scheme otherwise -->
    <data android:scheme="masamune" android:host="oauth" />
  </intent-filter>
</activity>
```

Prefer an **https App Link** (`autoVerify`, with the assetlinks.json the vendor
requires) over a custom scheme where the vendor allows it: a custom scheme can be
claimed by any other installed app, which turns the redirect into an interception
surface. Where only a custom scheme is available, PKCE is what stops a stolen
code being redeemed — which is the second reason PKCE is not optional here.

`singleTask` so the redirect lands in the existing task rather than stacking a
second copy of the app.

## The gotcha that will bite whoever implements this

**A provider's OAuth tokens usually do NOT authenticate against the same endpoint
its API keys do.** Wiring an OAuth token into the existing API-key adapter yields
401s that read as a bad token rather than a wrong host:

| Provider | API-key endpoint | OAuth (subscription) endpoint |
|---|---|---|
| Gemini | `generativelanguage.googleapis.com` | `cloudcode-pa.googleapis.com` (Code Assist) |
| ChatGPT account | — (no API-key equivalent) | `chatgpt.com/backend-api/` |
| OpenAI Platform | `api.openai.com` | same host |

So the credential **type** branches the adapter, not just the header:

```kotlin
val service = when (cred) {
    is OAuthCredential  -> GeminiCodeAssistService(cred)   // different host entirely
    is ApiKeyCredential -> GeminiGenerativeService(cred)
}
```

Same provider key, different transport, transparent to the chat layer.

## Token handling

- Refresh token in `EncryptedSharedPreferences` (Keystore-backed), never plain
  prefs, never the ledger, never Diagnostics, never an error string.
- Refresh proactively on `expires_at`, and once reactively on a 401 before
  surfacing a failure — a user whose token lapsed mid-session should not be shown
  an error they cannot act on.
- Sign-out revokes at the vendor where an endpoint exists, and always clears
  local state. "Signed out" that leaves a live refresh token is a lie.

## Resolution order

1. A subscription credential this app holds, obtained by the redirect flow above.
2. An API key. **Kept, but demoted** — one of two paths, no longer the only one.
   Some users do use keys, and deleting a working path to make a point is its own
   kind of wrong.

## Rejected: recycling another CLI's credential file

An earlier draft of this document proposed reading OAuth state from an
already-authenticated CLI (`~/.gemini/oauth_creds.json`, `~/.codex/auth.json`)
through Masamune's existing Termux bridge. **That is not the design.** The user
was explicit: the flow redirects to the vendor and back.

It was also the wrong shape regardless — it needs a second app installed and
signed in, it reaches across an app sandbox boundary to read another process's
credentials, and it gives the user no visible, revocable "signed in as" state.
Recorded here so it does not get re-proposed.

## The bound that must hold

Any provider whose flow cannot be made to genuinely work ships **disabled, with a
visible sentence naming what is missing**. A *Sign in* button that opens nothing,
or that appears to succeed and then fails on first use, is worse than no button —
and it is precisely the class of defect this campaign exists to remove.
