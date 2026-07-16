const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = id => document.getElementById(id);
const defaults = {protocol:'masque',scanMode:'balanced',ipMode:'v4',obfuscation:'firewall',masqueTransport:'h3',socksAddress:'127.0.0.1:1819',allowRemoteListener:false,peer:'',wgKeepalive:5,stallTimeout:90,watchdog:true,configPath:'',wgConfigPath:'',masqueConfigPath:'',quickReconnect:true};
let settings = {...defaults};
let state = 'disconnected';
let logs = [];
let saveTimer;

function selectSegment(id, value) { [...$(id).querySelectorAll('button')].forEach(b => b.classList.toggle('active', b.dataset.value === value)); }
function segmentValue(id) { return $(id).querySelector('.active')?.dataset.value; }
function setOptions(el, options, selected) { el.replaceChildren(...options.map(([value,label]) => { const o=document.createElement('option');o.value=value;o.textContent=label;o.selected=value===selected;return o; })); }
function syncProtocol() {
  settings.protocol = segmentValue('protocol');
  const masque = settings.protocol === 'masque';
  $('transport-field').classList.toggle('hidden', !masque);
  $('keepalive-field').classList.toggle('hidden', masque);
  const options = masque ? [['firewall','Firewall'],['gfw','GFW'],['off','Off']] : [['balanced','Balanced'],['aggressive','Aggressive'],['light','Light'],['off','Off']];
  if (!options.some(([v]) => v === settings.obfuscation)) settings.obfuscation = options[0][0];
  setOptions($('obfuscation'), options, settings.obfuscation);
}
function readSettings() {
  return {protocol:segmentValue('protocol'),scanMode:$('scanMode').value,ipMode:$('ipMode').value,obfuscation:$('obfuscation').value,masqueTransport:segmentValue('transport'),socksAddress:$('socksAddress').value.trim(),allowRemoteListener:$('allowRemote').checked,peer:$('peer').value.trim(),wgKeepalive:Number($('keepalive').value),stallTimeout:Number($('stallTimeout').value),watchdog:$('watchdog').checked,configPath:$('configPath').value.trim(),wgConfigPath:$('wgConfigPath').value.trim(),masqueConfigPath:$('masqueConfigPath').value.trim(),quickReconnect:$('quickReconnect').checked};
}
function renderSettings(s) {
  settings={...defaults,...s}; selectSegment('protocol',settings.protocol); selectSegment('transport',settings.masqueTransport); $('scanMode').value=settings.scanMode;$('ipMode').value=settings.ipMode;$('socksAddress').value=settings.socksAddress;$('allowRemote').checked=settings.allowRemoteListener;$('peer').value=settings.peer;$('keepalive').value=settings.wgKeepalive;$('stallTimeout').value=settings.stallTimeout;$('watchdog').checked=settings.watchdog;$('quickReconnect').checked=settings.quickReconnect;$('configPath').value=settings.configPath;$('wgConfigPath').value=settings.wgConfigPath;$('masqueConfigPath').value=settings.masqueConfigPath;syncProtocol();$('socks-display').textContent=settings.socksAddress;
}
function queueSave() { settings=readSettings();$('socks-display').textContent=settings.socksAddress||'—';clearTimeout(saveTimer);saveTimer=setTimeout(()=>invoke('save_settings',{settings}).catch(showError),350); }
function showError(error) { const text=typeof error==='string'?error:error?.message||String(error);$('toast').textContent=text;$('toast').className='show error';setTimeout(()=>{$('toast').className='';},5000); }
function toast(text) { $('toast').textContent=text;$('toast').className='show';setTimeout(()=>{$('toast').className='';},2500); }
function setState(next, endpoint, message) {
  state=next;const labels={disconnected:'Disconnected',scanning:'Scanning',connecting:'Connecting',connected:'Connected',reconnecting:'Reconnecting',error:'Error'};const titles={disconnected:'Ready to connect',scanning:'Finding a working endpoint…',connecting:'Establishing tunnel…',connected:'Your local proxy is ready',reconnecting:'Restoring the tunnel…',error:'Connection needs attention'};
  $('status').className=`status ${next}`;$('status').querySelector('b').textContent=labels[next]||next;$('status-title').textContent=titles[next]||next;if(message)$('status-message').textContent=message;if(endpoint)$('endpoint').textContent=endpoint;
  const active=!['disconnected','error'].includes(next);$('connect').disabled=active;$('disconnect').disabled=!active;$('test').disabled=next!=='connected';
}
function addLog(line) { if(logs.length===0&&$('logs').textContent==='Waiting for Aether…')logs=[];logs.push(line);if(logs.length>2000)logs.splice(0,logs.length-2000);$('logs').textContent=logs.join('\n');$('logs').scrollTop=$('logs').scrollHeight; }
async function connect() { try { settings=readSettings();await invoke('save_settings',{settings});logs=[];$('logs').textContent='';await invoke('connect',{settings}); } catch(e){setState('error',null,String(e));showError(e);} }
async function disconnect() { try { await invoke('disconnect');setState('disconnected',null,'Aether has stopped.'); } catch(e){showError(e);} }
async function testConnection() { $('test').disabled=true;$('test').textContent='Testing…';try{const result=await invoke('connection_test',{settings:readSettings()});const dl=$('trace');dl.replaceChildren();for(const line of result.trim().split(/\r?\n/)){const [key,...rest]=line.split('=');const dt=document.createElement('dt');dt.textContent=key;const dd=document.createElement('dd');dd.textContent=rest.join('=');dl.append(dt,dd);}$('testResult').classList.remove('hidden');}catch(e){showError(e);}finally{$('test').disabled=state!=='connected';$('test').textContent='Test connection';} }

$('protocol').addEventListener('click',e=>{if(e.target.dataset.value){selectSegment('protocol',e.target.dataset.value);settings.protocol=e.target.dataset.value;syncProtocol();queueSave();}});
$('transport').addEventListener('click',e=>{if(e.target.dataset.value){selectSegment('transport',e.target.dataset.value);queueSave();}});
document.querySelectorAll('input,select').forEach(el=>el.addEventListener('change',queueSave));
$('connect').addEventListener('click',connect);$('disconnect').addEventListener('click',disconnect);$('test').addEventListener('click',testConnection);
$('reset').addEventListener('click',()=>{renderSettings(defaults);queueSave();toast('Defaults restored');});
$('clearLogs').addEventListener('click',()=>{logs=[];$('logs').textContent='';});
$('copyLogs').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(logs.join('\n'));toast('Logs copied');}catch(e){showError(e);}});
$('closeTest').addEventListener('click',()=>$('testResult').classList.add('hidden'));

await listen('aether-log',e=>addLog(e.payload));
await listen('aether-status',e=>setState(e.payload.state,e.payload.endpoint,e.payload.message));
await listen('tray-connect',connect);
try { renderSettings(await invoke('load_settings')); } catch(e) { renderSettings(defaults);showError(e); }
setInterval(async()=>{if(state==='connected'){try{const seconds=await invoke('elapsed');const h=String(Math.floor(seconds/3600)).padStart(2,'0'),m=String(Math.floor(seconds%3600/60)).padStart(2,'0'),s=String(seconds%60).padStart(2,'0');$('elapsed').textContent=`${h}:${m}:${s}`;}catch{}}},1000);