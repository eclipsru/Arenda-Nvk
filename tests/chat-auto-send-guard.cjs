// Chat guard: opening a tool conversation must not create an outgoing message or photo.
// The tool remains visible as a non-message context card, and attachments wait for Send.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const root = path.join(__dirname, '..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const cabinet = read('cabinet.html');
const chat = read('chat.html');
const sb = read('assets/sb.js');
const tool = read('tool.html');
const nativePatch = read('android/patches/IvaPatch.java');
const nativeApply = read('android/patches/apply.py');

assert.ok(!cabinet.includes('Вопрос по инструменту'), 'cabinet: preset question must not be sent or prefilled');
assert.match(cabinet, /openChatWith\(t\.owner_email\|\|OWNER_EMAIL,'',t\)/,
  'cabinet: tool chat must pass a context object, not a preset message');
assert.match(cabinet, /function chatToolHtml\(t\)/, 'cabinet: tool context card is missing');
assert.match(cabinet, /row\.tool_id=S\.chatTool\.id/, 'cabinet: manual message keeps the tool context');

const cabinetPhoto = cabinet.slice(cabinet.indexOf("$('mFile').onchange"), cabinet.indexOf('async function sendMsg'));
assert.doesNotMatch(cabinetPhoto, /app_messages.*POST/, 'cabinet: selecting a photo must not send it automatically');

assert.match(tool, /&tool=' \+ encodeURIComponent\(ad\.id\)/,
  'tool page: conversation must carry the tool context');
assert.match(chat, /function toolContextHtml\(\)/, 'chat: tool context card is missing');
assert.match(chat, /pendingImg/, 'chat: photo must be a draft until Send');
assert.doesNotMatch(chat, /TOOL = null;\s*\/\* привязка только к первому сообщению \*\//,
  'chat: tool context must not disappear after the first manual message');

const chatPhoto = chat.slice(chat.indexOf("$('thFile').addEventListener"), chat.indexOf('async function doSend'));
assert.doesNotMatch(chatPhoto, /pushMessage/, 'chat: selecting a photo must not send it automatically');

assert.match(sb, /function subscribeMessages\(onInsert\)/, 'sb: realtime subscription is missing');
assert.match(sb, /function reconnect\(\)/, 'sb: realtime subscription has no reconnect');
assert.match(sb, /access_token: A\.tok/, 'sb: realtime join has no current access token');
assert.match(chat, /\}, 5000\);/, 'chat: REST fallback must refresh messages every 5 seconds');
assert.match(cabinet, /S\.chatSub=subscribeMessages\(onCabinetMessage\)/,
  'cabinet: realtime subscription is missing');

// Native APK: 10.28 is deliberately a minimal diagnostic build. The web fallback
// remains enabled, but all native chat polling is removed so the 10.22 hypothesis
// is tested without retaining any timer or loadChat call in the APK.
assert.doesNotMatch(nativePatch, /CHAT_REFRESH_MS|CHAT_TICK|chatPoll|getDeclaredMethod\("loadChat"\)/,
  'native: diagnostic build must contain no chat polling');
assert.match(nativeApply, /onTab\(Landroid\/app\/Activity;Ljava\/lang\/String;\)V/,
  'native: stable showTab hook must remain present');

console.log('Чат: автоотправка отключена, контекст инструмента сохраняется, веб realtime включён, native polling отключён для диагностики');
