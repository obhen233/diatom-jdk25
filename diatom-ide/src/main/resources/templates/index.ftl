<!DOCTYPE html>
<html lang="zh-CN">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>diatom-ide</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>☕</text></svg>">
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }
        html, body, #app { height: 100%; width: 100%; }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', Arial, sans-serif;
            -webkit-font-smoothing: antialiased;
        }
    </style>
</head>
<body>
    <div id="app"></div>
    <#-- Vue构建产物会被复制到 /static 目录，通过以下方式引入 -->
    <#-- 开发时使用 npm run dev，生产构建后由 maven 自动复制到 classpath:/static/ -->
    <script type="module" src="/assets/index.js"></script>
    <link rel="stylesheet" href="/assets/index.css">
</body>
</html>
