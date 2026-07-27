const { defineConfig } = require('@vue/cli-service')
const apiTarget = process.env.VUE_APP_API_TARGET || 'http://localhost:8080'

module.exports = defineConfig({
  transpileDependencies: true,
  publicPath: process.env.NODE_ENV === 'production' ? '/search/' : '/',
  devServer: {
    port: 8081,
    host: '0.0.0.0',  // 允许局域网访问
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      },
      '/actuator': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  },
  css: {
    loaderOptions: {
      sass: {
        additionalData: `@import "@/styles/variables.scss";`
      }
    }
  }
})
