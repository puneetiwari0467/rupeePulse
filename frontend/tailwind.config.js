/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"Plus Jakarta Sans"', 'Inter', 'system-ui', '-apple-system', 'sans-serif']
      },
      colors: {
        canvas: '#F2F5FA',
        navy: {
          950: '#0B1736',
          900: '#10214B',
          800: '#19316B',
          700: '#26448A'
        }
      },
      boxShadow: {
        soft: '0 4px 24px -4px rgba(16, 33, 75, 0.06), 0 1px 3px rgba(16, 33, 75, 0.04)',
        float: '0 18px 44px -10px rgba(16, 33, 75, 0.14)'
      }
    }
  },
  plugins: []
};
