Certificate static site — deploy instructions

Overview
- This folder contains a static HTML page (`index.html`) built with Tailwind (CDN) that reproduces the neon/cyberpunk certificate style shown in your image.
- The page accepts query parameters so you can pre-fill values when linking, for example:

  https://yisuscompany.com/?student=Juan%20Perez&course=Curso%20Kotlin&score=9.5/10&tasks=12/12&progress=100%25&status=APROBADO&date=13/12/2025&certid=CERT-2025-0001

Files
- `index.html` — static certificate page (Tailwind CDN + Google Fonts). No build required.

Recommended hosting: Cloudflare Pages (preferred)
- Cloudflare Pages is easy to use and works well with custom domains that use Cloudflare DNS.

Quick deploy using Cloudflare Pages
1. Create or log in to your Cloudflare account.
2. Go to Pages → Create a project → Connect your Git provider (GitHub/GitLab) and select this repository, or choose "Start without a Git provider" and upload the `web_certificate` folder content.
3. If connecting a repo, set the build configuration:
   - Framework: None
   - Build command: (leave blank)
   - Build output directory: `web_certificate` (or root if you place `index.html` at repo root)
4. Deploy. After the first deploy completes you'll get a `*.pages.dev` domain.

Set custom domain `yisuscompany.com`
- If you manage DNS with Cloudflare for `yisuscompany.com`, the easiest path:
  1. In Pages → Settings → Custom domains → Add a custom domain: `yisuscompany.com`.
  2. Cloudflare will automatically verify and create the required records when domain uses Cloudflare DNS.
  3. Ensure the domain is proxied (orange cloud) if you want Cloudflare features; Pages will provision TLS automatically.

- If your DNS is elsewhere, Cloudflare Pages will show a verification record to add (CNAME or TXT). Follow the Pages UI instructions.

Notes about root domain (apex) vs www
- Cloudflare Pages supports using the apex (yisuscompany.com) when your DNS is managed by Cloudflare. If you cannot add CNAME at apex with your DNS provider, use `www.yisuscompany.com` as the Pages custom domain and add a DNS `A` or ALIAS/ANAME per your DNS host, then redirect apex to `www`.

Alternative quick deploy: Netlify
- Drag-and-drop the `web_certificate` folder to Netlify Drop, then configure custom domain `yisuscompany.com` in Netlify settings and add the DNS records Netlify shows (CNAME / A records).

Testing locally
- Open `web_certificate/index.html` in your browser. To set values use query string parameters as shown above.

Want me to deploy it?
- I can push the `web_certificate` folder to your repository and configure a Cloudflare Pages deployment if you give me permission to commit, or I can provide exact `git` commands you can run locally. Tell me how you prefer to proceed.
