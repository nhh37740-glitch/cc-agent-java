const endpoint = new URL('api/settings/deepseek', window.location.href);
const statusElement = document.getElementById('key-status');
const resultElement = document.getElementById('key-result');
const keyInput = document.getElementById('api-key');
const hostname = window.location.hostname.toLowerCase();
const ipv4Parts = hostname.split('.');
const isLoopback = hostname === 'localhost' || hostname === '[::1]' || hostname === '::1'
    || (ipv4Parts.length === 4 && ipv4Parts[0] === '127'
        && ipv4Parts.every(part => /^\d+$/.test(part) && Number(part) <= 255));
const canEditKey = window.location.protocol === 'https:'
    || (window.location.protocol === 'http:' && isLoopback);

if (canEditKey) {
    document.getElementById('key-form').hidden = false;
    document.getElementById('remove-key').hidden = false;
    keyInput.disabled = false;
} else {
    resultElement.textContent = '当前入口使用 HTTP。请通过 HTTPS 打开此页面后再输入 DeepSeek API Key。';
}

function showStatus(status) {
    const source = status.source === 'browser-session' ? '当前浏览器会话'
        : status.source === 'environment' ? '服务器环境变量' : '未配置';
    statusElement.textContent = status.configured
        ? `已配置（${source}）：${status.masked}` : '未配置 DeepSeek API Key';
}

async function callSettings(method, body) {
    if (method !== 'GET' && !canEditKey) {
        throw new Error('当前入口不能传输 DeepSeek API Key；请使用 HTTPS 或本机回环地址。');
    }
    const response = await fetch(endpoint, {
        method,
        credentials: 'same-origin',
        cache: 'no-store',
        headers: body === undefined ? { 'X-Agent-Config': 'same-origin' }
            : { 'Content-Type': 'application/json', 'X-Agent-Config': 'same-origin' },
        body: body === undefined ? undefined : JSON.stringify(body)
    });
    if (!response.ok) {
        throw new Error(`配置请求失败（HTTP ${response.status}）`);
    }
    return response.json();
}

async function refreshStatus() {
    try {
        showStatus(await callSettings('GET'));
    } catch (error) {
        statusElement.textContent = error.message;
    }
}

document.getElementById('key-form').addEventListener('submit', async (event) => {
    event.preventDefault();
    if (!canEditKey) return;
    const apiKey = keyInput.value;
    keyInput.value = '';
    try {
        showStatus(await callSettings('PUT', { apiKey }));
        resultElement.textContent = '密钥已保存到当前浏览器会话。';
    } catch (error) {
        resultElement.textContent = error.message;
    }
});

document.getElementById('remove-key').addEventListener('click', async () => {
    try {
        showStatus(await callSettings('DELETE'));
        resultElement.textContent = '已移除当前浏览器会话的密钥。';
    } catch (error) {
        resultElement.textContent = error.message;
    }
});

refreshStatus();
