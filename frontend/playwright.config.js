import { defineConfig } from '@playwright/test';
import { existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';

const win = process.platform === 'win32';
const javaHome = process.env.JAVA_HOME;
const java = javaHome ? join(javaHome, 'bin', win ? 'java.exe' : 'java') : 'java';
const jar = fileURLToPath(new URL(process.env.FOLDPULSE_TEST_RELEASE === 'azure'
  ? '../target/azure/rupeepulse-1.1.0.jar' : '../target/rupeepulse-1.1.0.jar', import.meta.url));
const chrome = win && existsSync(join(process.env.ProgramFiles, 'Google', 'Chrome', 'Application', 'chrome.exe'));
const baseURL = 'http://127.0.0.1:18962';

export default defineConfig({
  testDir: './e2e',
  workers: 2,
  retries: 0,
  reporter: [['list'], ['json', { outputFile: 'test-results/results.json' }]],
  use: { baseURL, channel: chrome ? 'chrome' : undefined, screenshot: 'only-on-failure' },
  projects: [
    { name: 'desktop', use: { viewport: { width: 1440, height: 900 } } },
    { name: 'phone', use: { viewport: { width: 390, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 1 } }
  ],
  webServer: {
    command: `"${java}" -jar "${jar}" --server.address=127.0.0.1 --server.port=18962 --AZURE_OPENAI_API_KEY= --AZURE_OPENAI_ENDPOINT=`,
    url: `${baseURL}/api/dashboard`,
    reuseExistingServer: false,
    timeout: 60000
  }
});
