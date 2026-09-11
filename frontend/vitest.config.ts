import { defineConfig } from 'vitest/config';

/**
 * Runner configuration for the Angular unit-test builder.
 *
 * The tests need a DOM: components render into one, and the actor identity is
 * kept in localStorage. Vitest defaults to a bare Node environment where
 * neither exists, so jsdom is selected explicitly.
 */
export default defineConfig({
  test: {
    environment: 'jsdom',
  },
});
