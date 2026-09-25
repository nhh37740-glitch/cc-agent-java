import markdown, os, subprocess, sys

fname = sys.argv[1] if len(sys.argv) > 1 else 'cc-agent-java-architecture'
base = rf'C:\Users\Z\Documents\project\code\cc-agent-java\docs\plans\{fname}'
md_file = base + '.md'
html_file = base + '.html'
pdf_file = base + '.pdf'

with open(md_file, encoding='utf-8') as f:
    md = f.read()

css = '''
@page { size: A4; margin: 2.2cm 2.5cm; }
body { font-family: "Microsoft YaHei","SimHei",sans-serif; font-size: 11pt; line-height: 1.8; color: #2d2d2d; }
h1 { font-size: 20pt; color: #1a1a2e; border-bottom: 3px solid #4169e1; padding-bottom: 6px; page-break-before: always; margin-top: 0; }
h1:first-of-type { page-break-before: avoid; }
h2 { font-size: 15pt; color: #2c3e50; border-left: 4px solid #4169e1; padding-left: 12px; margin-top: 30px; page-break-after: avoid; }
h3 { font-size: 13pt; color: #34495e; margin-top: 22px; }
p { margin: 10px 0; text-align: justify; }
code { background: #f5f5f5; color: #c7254e; padding: 2px 6px; border-radius: 3px; font-size: 10pt; font-family: "Consolas",monospace; }
pre { background: #f8f8f8; color: #2d2d2d; border: 1px solid #e0e0e0; border-left: 4px solid #4169e1; padding: 14px 18px; border-radius: 4px; font-size: 9.5pt; line-height: 1.5; white-space: pre-wrap; page-break-inside: avoid; }
table { border-collapse: collapse; width: 100%; margin: 14px 0; page-break-inside: avoid; font-size: 10pt; }
th, td { border: 1px solid #d0d0d0; padding: 10px 12px; text-align: left; }
th { background: #4169e1; color: #fff; font-weight: 600; }
tr:nth-child(even) { background: #f8f9fe; }
blockquote { border-left: 4px solid #4169e1; margin: 12px 0; padding: 10px 18px; color: #555; background: #f0f4ff; border-radius: 0 4px 4px 0; }
strong { color: #1a1a2e; }
'''

html_body = markdown.markdown(md, extensions=['tables', 'fenced_code'])
html = '<html><head><meta charset="utf-8"><style>%s</style></head><body>%s</body></html>' % (css, html_body)

with open(html_file, 'w', encoding='utf-8') as f:
    f.write(html)

chrome = r'C:\Program Files\Google\Chrome\Application\chrome.exe'
if not os.path.exists(chrome):
    chrome = r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe'

subprocess.run([chrome, '--headless', '--disable-gpu', '--no-sandbox', '--print-to-pdf=' + pdf_file, html_file], timeout=30)
print('PDF: %s (%s bytes)' % (pdf_file, os.path.getsize(pdf_file)))
