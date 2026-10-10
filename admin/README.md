# admin

The staff app. Talks to admin-bff (:8083) and nothing else. docs/reference/admin.md.

```bash
cd admin
npm ci
cp .env.example .env.local
npm run dev          # http://localhost:3001
npm test && npm run lint && npm run build
```

There is no way to register: the first admin is made with the CLI in that page, and logs in here.
