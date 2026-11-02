---
name: website-audit
description: Full audit of a website or online store (design, trust, product pages, conversion, SEO, speed, social proof) with scores and a PDF report.
---
# Website / store audit

Steps:
1. `browse_url` the home page. Note title, meta description, H1, load time, page size, images without alt, JSON-LD.
2. Browse 3–6 more pages: a product page, a collection/category, cart or checkout entry, about/contact, policies (shipping, returns, privacy).
3. `web_search` the brand name + "reviews", and the domain, to check reputation and social presence.
4. Score each area out of 10 with one-line evidence:
   | Area | Score | Evidence |
   - Design & mobile UX
   - Trust (policies, contact, COD/payment info, reviews, SSL, about page)
   - Product information (descriptions, ingredients/specs, photos, prices, variants)
   - Conversion (CTA clarity, cart flow, offers, WhatsApp/chat, speed)
   - Content & SEO (titles, meta, headings, alt text, structured data, blog)
   - Social proof (reviews, ratings, UGC, social links)
   - Speed & technical (load ms, page weight, broken links, canonical)
   Overall = average, one decimal.
5. List the top 10 fixes ordered by impact ÷ effort, each concrete ("Add meta description to product pages: …").
6. Load the `pdf` skill and produce the PDF with create_pdf. Reply with the score table + top 3 fixes.
Be honest: if a page could not be opened, say so instead of guessing.
