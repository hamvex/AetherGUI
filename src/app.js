const tauri = window.__TAURI__ || {
  core: { invoke: async command => command === 'load_settings' ? {} : command === 'elapsed' ? 0 : command === 'connection_test' ? 'ip=203.0.113.1\nwarp=on\nhttp=http/3' : undefined },
  event: { listen: async () => () => {} },
  opener: { openUrl: async () => {} }
};
const { invoke } = tauri.core;
const { listen } = tauri.event;
const { openUrl } = tauri.opener;

const $ = id => document.getElementById(id);
const defaults = {protocol:'masque',scanMode:'balanced',ipMode:'v4',obfuscation:'firewall',masqueTransport:'h3',socksAddress:'127.0.0.1:1819',allowRemoteListener:false,peer:'',wgKeepalive:5,stallTimeout:90,watchdog:true,configPath:'',wgConfigPath:'',masqueConfigPath:'',quickReconnect:true};
const stateLabels = {disconnected:'Disconnected',scanning:'Scanning',connecting:'Connecting',connected:'Connected',reconnecting:'Reconnecting',error:'Error'};
const stateTitles = {disconnected:'Ready to connect',scanning:'Finding a working endpoint…',connecting:'Establishing the tunnel…',connected:'Your local proxy is ready',reconnecting:'Restoring the tunnel…',error:'Connection needs attention'};
const stateMessages = {disconnected:'Recommended settings are already selected. Press Connect to begin.',scanning:'Aether is checking available endpoints. This can take a moment.',connecting:'A healthy endpoint was found. The tunnel is being negotiated.',connected:'Configure your applications to use the SOCKS5 address shown below.',reconnecting:'The previous connection was interrupted. Aether is trying to recover it.',error:'Review the message and Live logs, then adjust the profile or try again.'};
const wizardStorageKey = 'firstham-welcome-complete-v1';
let settings = {...defaults};
let state = 'disconnected';
let logs = [];
let saveTimer;
let wizardIndex = 0;

function selectSegment(id, value) {
  [...$(id).querySelectorAll('button')].forEach(button => button.classList.toggle('active', button.dataset.value === value));
}

function segmentValue(id) {
  return $(id).querySelector('.active')?.dataset.value;
}

function setOptions(element, options, selected) {
  element.replaceChildren(...options.map(([value, label]) => {
    const option = document.createElement('option');
    option.value = value;
    option.textContent = label;
    option.selected = value === selected;
    return option;
  }));
}

function syncProtocol() {
  settings.protocol = segmentValue('protocol');
  const masque = settings.protocol === 'masque';
  $('transport-field').classList.toggle('hidden', !masque);
  $('keepalive-field').classList.toggle('hidden', masque);
  const options = masque
    ? [['firewall','Firewall — recommended'],['gfw','GFW — stronger profile'],['off','Off — no obfuscation']]
    : [['balanced','Balanced — recommended'],['aggressive','Aggressive — maximum'],['light','Light — lower overhead'],['off','Off — no obfuscation']];
  if (!options.some(([value]) => value === settings.obfuscation)) settings.obfuscation = options[0][0];
  setOptions($('obfuscation'), options, settings.obfuscation);
  $('obfuscation-help').textContent = masque
    ? 'Firewall is recommended. Try GFW when the default profile is not enough.'
    : 'Balanced is recommended. Aggressive adds more overhead and should be a fallback.';
}

function readSettings() {
  return {
    protocol: segmentValue('protocol'),
    scanMode: $('scanMode').value,
    ipMode: $('ipMode').value,
    obfuscation: $('obfuscation').value,
    masqueTransport: segmentValue('transport'),
    socksAddress: $('socksAddress').value.trim(),
    allowRemoteListener: $('allowRemote').checked,
    peer: $('peer').value.trim(),
    wgKeepalive: Number($('keepalive').value),
    stallTimeout: Number($('stallTimeout').value),
    watchdog: $('watchdog').checked,
    configPath: $('configPath').value.trim(),
    wgConfigPath: $('wgConfigPath').value.trim(),
    masqueConfigPath: $('masqueConfigPath').value.trim(),
    quickReconnect: $('quickReconnect').checked
  };
}

function renderSettings(saved) {
  settings = {...defaults, ...saved};
  selectSegment('protocol', settings.protocol);
  selectSegment('transport', settings.masqueTransport);
  $('scanMode').value = settings.scanMode;
  $('ipMode').value = settings.ipMode;
  $('socksAddress').value = settings.socksAddress;
  $('allowRemote').checked = settings.allowRemoteListener;
  $('peer').value = settings.peer;
  $('keepalive').value = settings.wgKeepalive;
  $('stallTimeout').value = settings.stallTimeout;
  $('watchdog').checked = settings.watchdog;
  $('quickReconnect').checked = settings.quickReconnect;
  $('configPath').value = settings.configPath;
  $('wgConfigPath').value = settings.wgConfigPath;
  $('masqueConfigPath').value = settings.masqueConfigPath;
  syncProtocol();
  $('socks-display').textContent = settings.socksAddress;
}

function queueSave() {
  settings = readSettings();
  $('socks-display').textContent = settings.socksAddress || 'Not configured';
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => invoke('save_settings', { settings }).catch(showError), 350);
}

function showError(error) {
  const text = typeof error === 'string' ? error : error?.message || String(error);
  $('toast').textContent = text;
  $('toast').className = 'show error';
  setTimeout(() => { $('toast').className = ''; }, 5000);
}

function toast(text) {
  $('toast').textContent = text;
  $('toast').className = 'show';
  setTimeout(() => { $('toast').className = ''; }, 2600);
}

function setState(next, endpoint, message) {
  state = next;
  const label = stateLabels[next] || next;
  $('status').className = `status ${next}`;
  $('status').querySelector('b').textContent = label;
  $('status-title').textContent = stateTitles[next] || next;
  $('status-message').textContent = message || stateMessages[next] || '';
  $('sidebar-state').textContent = label;
  $('sidebar-dot').className = `state-dot ${next}`;
  $('connection-symbol').textContent = next === 'connected' ? '✓' : next === 'error' ? '!' : next === 'disconnected' ? '↗' : '…';
  if (endpoint) $('endpoint').textContent = endpoint;
  if (next === 'disconnected' && !endpoint) $('endpoint').textContent = 'Not selected';
  const active = !['disconnected','error'].includes(next);
  $('connect').disabled = active;
  $('disconnect').disabled = !active;
  $('test').disabled = next !== 'connected';
}

function addLog(line) {
  if (logs.length === 0 && $('logs').textContent === 'Waiting for Aether…') logs = [];
  logs.push(line);
  if (logs.length > 2000) logs.splice(0, logs.length - 2000);
  $('logs').textContent = logs.join('\n');
  $('logs').scrollTop = $('logs').scrollHeight;
}

async function connect() {
  try {
    showView('dashboard');
    settings = readSettings();
    await invoke('save_settings', { settings });
    logs = [];
    $('logs').textContent = '';
    await invoke('connect', { settings });
  } catch (error) {
    setState('error', null, String(error));
    showError(error);
  }
}

async function disconnect() {
  try {
    await invoke('disconnect');
    setState('disconnected', null, 'Aether has stopped. Press Connect when you are ready.');
    $('elapsed').textContent = '00:00:00';
  } catch (error) {
    showError(error);
  }
}

async function testConnection() {
  $('test').disabled = true;
  $('test').textContent = 'Verifying…';
  try {
    const result = await invoke('connection_test', { settings: readSettings() });
    const list = $('trace');
    list.replaceChildren();
    for (const line of result.trim().split(/\r?\n/)) {
      const [key, ...rest] = line.split('=');
      const term = document.createElement('dt');
      const description = document.createElement('dd');
      term.textContent = key;
      description.textContent = rest.join('=');
      list.append(term, description);
    }
    $('testResult').classList.remove('hidden');
    toast('Connection verified through Aether SOCKS5');
  } catch (error) {
    showError(error);
  } finally {
    $('test').disabled = state !== 'connected';
    $('test').textContent = 'Verify connection';
  }
}

function showView(name, anchor) {
  document.querySelectorAll('.view').forEach(view => view.classList.toggle('active', view.id === `view-${name}`));
  document.querySelectorAll('.nav-item').forEach(item => {
    const active = item.dataset.view === name;
    item.classList.toggle('active', active);
    if (active) item.setAttribute('aria-current', 'page'); else item.removeAttribute('aria-current');
  });
  window.scrollTo({ top: 0, behavior: 'instant' });
  if (anchor) requestAnimationFrame(() => $(anchor)?.scrollIntoView({ behavior: 'smooth', block: 'start' }));
}

function showWizard(start = 0) {
  wizardIndex = start;
  $('welcomeWizard').classList.remove('hidden');
  renderWizard();
  $('closeWizard').focus();
}

function closeWizard(complete = true) {
  $('welcomeWizard').classList.add('hidden');
  if (complete) localStorage.setItem(wizardStorageKey, '1');
}

function renderWizard() {
  document.querySelectorAll('.wizard-slide').forEach((slide, index) => slide.classList.toggle('active', index === wizardIndex));
  document.querySelectorAll('.wizard-progress i').forEach((dot, index) => dot.classList.toggle('active', index <= wizardIndex));
  $('wizardBack').disabled = wizardIndex === 0;
  $('wizardNext').textContent = wizardIndex === 4 ? 'Start connecting' : 'Next';
  $('wizardStep').textContent = `${wizardIndex + 1} of 5`;
}

function searchDocs(query) {
  const normalized = query.trim().toLowerCase();
  let visible = 0;
  document.querySelectorAll('.doc-section').forEach(section => {
    const match = !normalized || section.textContent.toLowerCase().includes(normalized);
    section.classList.toggle('hidden', !match);
    if (match) visible += 1;
  });
  $('noDocResults').classList.toggle('hidden', visible !== 0);
}

$('protocol').addEventListener('click', event => {
  if (!event.target.dataset.value) return;
  selectSegment('protocol', event.target.dataset.value);
  settings.protocol = event.target.dataset.value;
  syncProtocol();
  queueSave();
});
$('transport').addEventListener('click', event => {
  if (!event.target.dataset.value) return;
  selectSegment('transport', event.target.dataset.value);
  queueSave();
});
document.querySelector('.settings-panel').querySelectorAll('input,select').forEach(element => element.addEventListener('change', queueSave));
$('connect').addEventListener('click', connect);
$('disconnect').addEventListener('click', disconnect);
$('test').addEventListener('click', testConnection);
$('reset').addEventListener('click', () => { renderSettings(defaults); queueSave(); toast('Recommended defaults restored'); });
$('clearLogs').addEventListener('click', () => { logs = []; $('logs').textContent = ''; });
$('copyLogs').addEventListener('click', async () => {
  try { await navigator.clipboard.writeText(logs.join('\n')); toast('Logs copied to clipboard'); }
  catch (error) { showError(error); }
});
$('closeTest').addEventListener('click', () => $('testResult').classList.add('hidden'));
$('docSearch').addEventListener('input', event => searchDocs(event.target.value));
document.querySelectorAll('.nav-item').forEach(item => item.addEventListener('click', () => showView(item.dataset.view)));
document.querySelectorAll('[data-go]').forEach(button => button.addEventListener('click', () => showView(button.dataset.go, button.dataset.anchor)));
document.querySelectorAll('[data-external]').forEach(button => button.addEventListener('click', async () => {
  try { await openUrl(button.dataset.external); }
  catch (error) { showError(`Could not open the link: ${error}`); }
}));
$('showTour').addEventListener('click', () => showWizard(0));
$('closeWizard').addEventListener('click', () => closeWizard(true));
$('wizardBack').addEventListener('click', () => { if (wizardIndex > 0) { wizardIndex -= 1; renderWizard(); } });
$('wizardNext').addEventListener('click', () => {
  if (wizardIndex < 4) { wizardIndex += 1; renderWizard(); }
  else { closeWizard(true); showView('dashboard'); $('connect').focus(); }
});
$('welcomeWizard').addEventListener('click', event => { if (event.target === $('welcomeWizard')) closeWizard(true); });
document.addEventListener('keydown', event => { if (event.key === 'Escape' && !$('welcomeWizard').classList.contains('hidden')) closeWizard(true); });

await listen('aether-log', event => addLog(event.payload));
await listen('aether-status', event => setState(event.payload.state, event.payload.endpoint, event.payload.message));
await listen('tray-connect', connect);

try { renderSettings(await invoke('load_settings')); }
catch (error) { renderSettings(defaults); showError(error); }

setState('disconnected');
if (!localStorage.getItem(wizardStorageKey)) setTimeout(() => showWizard(0), 250);

setInterval(async () => {
  if (state !== 'connected') return;
  try {
    const seconds = await invoke('elapsed');
    const hours = String(Math.floor(seconds / 3600)).padStart(2, '0');
    const minutes = String(Math.floor(seconds % 3600 / 60)).padStart(2, '0');
    const remaining = String(seconds % 60).padStart(2, '0');
    $('elapsed').textContent = `${hours}:${minutes}:${remaining}`;
  } catch { /* The next state event will report process failures. */ }
}, 1000);
