/** @type {import('tailwindcss').Config} */
export default {
  content: [
    "./index.html",
    "./src/**/*.{vue,js,ts,jsx,tsx}",
  ],
  theme: {
    extend: {
      colors: {
        // 主色暖橙
        primary: '#FF7D00',
        'primary-light': '#FF9A3C',
        'primary-dark': '#E56E00',
        // 中性色
        'bg-light': '#F5F7FA',
        'text-main': '#333',
        'text-secondary': '#666',
        'text-muted': '#999',
      },
      borderRadius: {
        'card': '8px', // 统一圆角
      },
      boxShadow: {
        'card': '0 2px 8px rgba(0, 0, 0, 0.06)',
        'card-hover': '0 4px 16px rgba(0, 0, 0, 0.12)',
        'navbar': '0 1px 4px rgba(0, 0, 0, 0.08)',
      },
      maxWidth: {
        'page': '1200px', // 页面内容最大宽度
      },
    },
  },
  plugins: [],
}
