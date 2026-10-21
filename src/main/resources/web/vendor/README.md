# Bundled browser dependencies

These files are copied unchanged from the pinned npm release archives. No CDN
requests, npm install or frontend build step is needed at runtime or Maven build time.

| Library | Version | Source file | License |
| --- | --- | --- | --- |
| markdown-it | 15.0.2 | `dist/browser/markdown-it.umd.min.js` | MIT, `markdown-it.LICENSE` |
| @highlightjs/cdn-assets | 11.12.0 | `highlight.min.js` (common languages), `languages/powershell.min.js` | BSD-3-Clause, `highlight.LICENSE` |

Upstream sources and documentation:

- https://github.com/markdown-it/markdown-it
- https://github.com/highlightjs/cdn-release
- https://highlightjs.readthedocs.io/en/latest/api.html

Release archives (SHA-512 verified before extracting):

- https://registry.npmjs.org/markdown-it/-/markdown-it-15.0.2.tgz
  `sha512-q4IGxMv56jCqT4OCRCADBoDP3LO4MhmTXjFbphHPXs4g3j9Xg5RDnxqN8IF/3vIWEU+VCnUq+7JUg/cfy2E6Qw==`
- https://registry.npmjs.org/@highlightjs/cdn-assets/-/cdn-assets-11.12.0.tgz
  `sha512-KvOKXODaiFmId9xaq3xc5xCL66wVLUuOngDbO9B/kewbFTqdGbn2nJxNhN3H5R1cgDTVj6R8vH0zgiNDEGjpDw==`

Bundled JavaScript SHA-256:

- `markdown-it.min.js`: `635972B985228E8AF9F0143647C68616B7A3BB09F6946E7E4A52E43DCF5E7BE5`
- `highlight.min.js`: `8AB71EB09C51F501E5E25157D9CFF100E46CC29BCBFC744D0B746D451FCA7F53`
- `powershell.min.js`: `FC298B3E0DB362E531E6D58988B7A78F83DA19DF6B5D74DB75BC961B2BFBFD3D`
