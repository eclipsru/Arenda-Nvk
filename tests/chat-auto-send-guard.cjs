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

console.log('Чат: автоотправка отключена, контекст инструмента сохраняется, realtime и 5-секундный fallback включены');
