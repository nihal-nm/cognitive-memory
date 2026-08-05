import { defineConfig } from 'astro/config';
import mdx from '@astrojs/mdx';
import tailwindcss from '@tailwindcss/vite';
import { remarkBasePath } from './remark-base-path.mjs';
import { remarkMermaid } from './remark-mermaid.mjs';

const base = process.env.ASTRO_BASE || '/';
const site = process.env.ASTRO_SITE || 'https://rigazilla.github.io';

export default defineConfig({
  site,
  base,
  integrations: [mdx()],
  vite: {
    plugins: [tailwindcss()],
  },
  markdown: {
    remarkPlugins: [remarkMermaid, remarkBasePath],
    shikiConfig: {
      theme: 'github-dark',
      wrap: true,
    },
  },
  build: {
    format: 'directory',
  },
});
