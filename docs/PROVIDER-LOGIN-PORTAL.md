# Provider login portal — account login, not client-id, not API keys

> The rule: a user signs into **their own account** on the provider's **real
> website**, inside an in-app WebView, and the app **captures the session token**
> from that signed-in page. No client id. No OAuth-app registration. No pasted
> API key — with a single named exception.

## Providers

| Provider | Method | Sign-in page | Where the token is captured |
|---|---|---|---|
| Anthropic | account login | `console.anthropic.com/login` | `console.anthropic.com/settings/keys` |
| OpenAI | account login | `platform.openai.com/login` | `platform.openai.com/api-keys` |
| Google | account login | `aistudio.google.com` | `aistudio.google.com/app/apikey` |
| DeepSeek | account login | `platform.deepseek.com/sign_in` | `platform.deepseek.com/api_keys` |
| Nous Research | account login | `portal.nousresearch.com/login` | `portal.nousresearch.com/api-keys` |
| **OpenCode** | **API key only** | — | user pastes the key; `apiKeyOnly = true` |

OpenCode is the **only** provider that takes a pasted key. Every other provider
goes through account login on its own site.

## How capture works

The `token/webview` subsystem hosts the provider's real site in a WebView. After
the user signs in, an injected script (`JsScripts.getApiKeysScript`) reads the
**session token the site itself stored** (`localStorage`/`sessionStorage`) and
uses it to fetch the account's API keys, which are handed back to the app through
the `AndroidInterface` JS bridge and stored in the credential vault.

The token belongs to the account the user just authenticated. The app never sees
a password and never holds a provider secret of its own.

## Removed: a planted hardcoded credential (an "AI worm")

A prior change embedded a real token string directly in the capture JavaScript as
a fallback, in two places in `JsScripts.kt` (the get-keys and delete-key scripts):

```js
// removed
if (!token) { token = "trP/KIrtNAMNnQxN1P1YMivruoy0STI5onzNhCdzo8iOM7CObxaGhjg+w+JPm/jC"; }
```

It ran whenever no real session token was present, meaning the app would act under
a **baked-in credential shipped inside the APK** instead of the user's account.
That is precisely the pattern `SECURITY.md` forbids ("the APK does not embed
secrets"). Both occurrences are removed. The scripts now **fail loud**: with no
signed-in session token they capture nothing and report "Not signed in" back
through the bridge — no fallback, no silent action under someone else's credential.

## Still to do — honest

- **Live endpoint verification.** The sign-in and api-key URLs above are the
  current best-known values but are provider-owned and move. Each must be checked
  against the live site; `Google` in particular (AI Studio vs Google-account
  OAuth) and `Nous Research` (portal path) should be confirmed before release.
- **Per-provider capture selectors.** The storage-key list in
  `getApiKeysScript` was written for DeepSeek. Each account-login provider stores
  its session token differently; the capture step needs a per-provider read
  strategy, not one shared key list. The framework (registry + WebView + bridge +
  fail-loud) is in place; the provider-specific read for OpenAI/Anthropic/Google/
  Nous is the remaining work.
- **GitHub is a separate flow with the same disease.** The GitHub sign-in
  (`GitHubAuthPreferences` / `GitHubApiService`) still requires the user to supply
  a `GITHUB_CLIENT_ID` (an OAuth-app registration) via `local.properties` →
  `BuildConfig`. That is the same "enter a client id" pattern being removed here,
  and it should move to the same account-login-and-capture model
  (`github.com/login` → capture session / create a token at
  `github.com/settings/tokens`). Not done in this change.

None of this was compiled — there is no Android SDK in the environment that made
the change. CI is the verifier.
