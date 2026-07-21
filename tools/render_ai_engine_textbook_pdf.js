#!/usr/bin/env node
const fs = require("fs");
const path = require("path");
const { marked } = require("marked");
const { chromium } = require("playwright");

const root = path.resolve(__dirname, "..");
const outputDir = path.join(root, "output", "pdf");
const mdPath = path.join(outputDir, "ai_engine_self_study_textbook.md");
const htmlPath = path.join(outputDir, "ai_engine_self_study_textbook.html");
const pdfPath = path.join(outputDir, "ai_engine_self_study_textbook.pdf");

function buildHtml(markdown) {
  const body = marked.parse(markdown, {
    gfm: true,
    breaks: false,
  });
  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <title>AI 构图辅助相机 AI 引擎零基础自学教材</title>
  <style>
    @page {
      size: A4;
      margin: 18mm 16mm 18mm 16mm;
    }
    * {
      box-sizing: border-box;
    }
    body {
      margin: 0;
      color: #0f172a;
      font-family: "Hiragino Sans GB", "STHeiti", "PingFang SC", "Songti SC", sans-serif;
      font-size: 13px;
      line-height: 1.72;
      background: #ffffff;
    }
    h1, h2, h3 {
      color: #0f172a;
      line-height: 1.35;
      margin: 0 0 10px 0;
      break-after: avoid;
    }
    h1 {
      font-size: 24px;
      margin-top: 0;
      padding-top: 4px;
      border-bottom: 2px solid #dbeafe;
      padding-bottom: 8px;
      break-before: page;
    }
    h1:first-child {
      break-before: avoid;
      text-align: center;
      border-bottom: none;
      font-size: 30px;
      margin-top: 80px;
      margin-bottom: 16px;
    }
    h1:first-child + p {
      text-align: center;
      color: #475569;
      font-size: 14px;
      margin-bottom: 40px;
    }
    h2 {
      font-size: 18px;
      margin-top: 18px;
      color: #1e293b;
    }
    h3 {
      font-size: 15px;
      margin-top: 14px;
      color: #334155;
    }
    p {
      margin: 0 0 9px 0;
      text-align: justify;
    }
    blockquote {
      margin: 10px 0 14px 0;
      padding: 10px 12px;
      border-left: 4px solid #38bdf8;
      background: #f8fafc;
      color: #334155;
      break-inside: avoid;
    }
    ul, ol {
      margin: 0 0 10px 22px;
      padding: 0;
    }
    li {
      margin: 2px 0;
    }
    table {
      width: 100%;
      border-collapse: collapse;
      margin: 10px 0 14px 0;
      font-size: 11px;
      line-height: 1.45;
      break-inside: auto;
    }
    thead {
      display: table-header-group;
    }
    th {
      background: #e2e8f0;
      color: #0f172a;
      font-weight: 700;
    }
    th, td {
      border: 1px solid #cbd5e1;
      padding: 6px 7px;
      vertical-align: top;
      word-break: break-word;
    }
    pre {
      margin: 9px 0 12px 0;
      padding: 10px 12px;
      background: #0f172a;
      color: #e2e8f0;
      border-radius: 6px;
      white-space: pre-wrap;
      word-break: break-word;
      overflow-wrap: anywhere;
      font-family: "Menlo", "SF Mono", "Source Code Pro", "DejaVu Sans Mono", monospace;
      font-size: 10.5px;
      line-height: 1.45;
      break-inside: avoid;
    }
    code {
      font-family: "Menlo", "SF Mono", "Source Code Pro", "DejaVu Sans Mono", monospace;
      font-size: 0.92em;
    }
    p code, li code, td code {
      color: #be123c;
      background: #fff1f2;
      padding: 0 3px;
      border-radius: 3px;
    }
  </style>
</head>
<body>
${body}
</body>
</html>`;
}

async function main() {
  const markdown = fs.readFileSync(mdPath, "utf8");
  const html = buildHtml(markdown);
  fs.writeFileSync(htmlPath, html, "utf8");

  const edgePath = "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge";
  const launchOptions = fs.existsSync(edgePath)
    ? { headless: true, executablePath: edgePath }
    : { headless: true };
  const browser = await chromium.launch(launchOptions);
  const page = await browser.newPage();
  await page.setContent(html, { waitUntil: "load" });
  await page.pdf({
    path: pdfPath,
    format: "A4",
    printBackground: true,
    displayHeaderFooter: true,
    headerTemplate: `<div></div>`,
    footerTemplate: `
      <div style="font-family: 'Hiragino Sans GB', 'STHeiti', sans-serif; font-size: 8px; color: #64748b; width: 100%; padding: 0 16mm;">
        <span>AI-camera AI 引擎零基础自学教材</span>
        <span style="float: right;">第 <span class="pageNumber"></span> 页 / 共 <span class="totalPages"></span> 页</span>
      </div>
    `,
    margin: {
      top: "18mm",
      right: "16mm",
      bottom: "18mm",
      left: "16mm",
    },
  });
  await browser.close();
  console.log(pdfPath);
  console.log(htmlPath);
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
