import { applyTranslations, getCurrentLanguage, setCurrentLanguage, t } from './i18n.js';
import { docsByLanguage } from './docs.js';

const tauri = window.__TAURI__ || {
  core: { invoke: async command => command === 'load_settings' ? {} : command === 'elapsed' ? 0 : command === 'connection_test' ? 'ip=203.0.113.1\nwarp=on\nhttp=http/3' : undefined },
  event: { listen: async () => () => {} },
  opener: { openUrl: async () => {} }
};
const { invoke } = tauri.core;
const { listen } = tauri.event;
const { openUrl } = tauri.opener;
const $ = id => document.getElementById(id);
const defaults = {language:'en',protocol:'masque',scanMode:'balanced',ipMode:'v4',obfuscation:'firewall',masqueTransport:'h3',socksAddress:'127.0.0.1:1819',allowRemoteListener:false,peer:'',wgKeepalive:5,stallTimeout:90,watchdog:true,configPath:'',wgConfigPath:'',masqueConfigPath:'',quickReconnect:true};
const wizardStorageKey = 'firstham-welcome-complete-v1';
let settings = {...defaults};
let state = 'disconnected';
let activeEndpoint = '';
let logs = [];
let saveTimer;
let wizardIndex = 0;

function selectSegment(id, value) {
  [...$(id).querySelectorAll('button')].forEach(button => button.classList.toggle('active', button.dataset.value === value));
}
function segmentValue(id) { return $(id).querySelector('.active')?.dataset.value; }
function setOptions(element, options, selected) {
  element.replaceChildren(...options.map(([value, label]) => {
    const option = document.createElement('option'); option.value = value; option.textContent = label; option.selected = value === selected; return option;
  }));
}

function renderChoiceOptions() {
  setOptions($('scanMode'), [['turbo',t('scan.turbo')],['balanced',t('scan.balanced')],['thorough',t('scan.thorough')],['stealth',t('scan.stealth')]], settings.scanMode);
  setOptions($('ipMode'), [['v4',t('ip.v4')],['v6',t('ip.v6')],['both',t('ip.both')]], settings.ipMode);
  syncProtocol();
}

function syncProtocol() {
  settings.protocol = segmentValue('protocol') || settings.protocol;
  const masque = settings.protocol === 'masque';
  $('transport-field').classList.toggle('hidden', !masque);
  $('keepalive-field').classList.toggle('hidden', masque);
  const options = masque
    ? [['firewall',t('obfuscation.firewall')],['gfw',t('obfuscation.gfw')],['off',t('obfuscation.off')]]
    : [['balanced',t('obfuscation.balanced')],['aggressive',t('obfuscation.aggressive')],['light',t('obfuscation.light')],['off',t('obfuscation.off')]];
  if (!options.some(([value]) => value === settings.obfuscation)) settings.obfuscation = options[0][0];
  setOptions($('obfuscation'), options, settings.obfuscation);
  $('obfuscation-help').textContent = t(masque ? 'help.obfuscationMasque' : 'help.obfuscationOther');
}

function readSettings() {
  return {language:getCurrentLanguage(),protocol:segmentValue('protocol'),scanMode:$('scanMode').value,ipMode:$('ipMode').value,obfuscation:$('obfuscation').value,masqueTransport:segmentValue('transport'),socksAddress:$('socksAddress').value.trim(),allowRemoteListener:$('allowRemote').checked,peer:$('peer').value.trim(),wgKeepalive:Number($('keepalive').value),stallTimeout:Number($('stallTimeout').value),watchdog:$('watchdog').checked,configPath:$('configPath').value.trim(),wgConfigPath:$('wgConfigPath').value.trim(),masqueConfigPath:$('masqueConfigPath').value.trim(),quickReconnect:$('quickReconnect').checked};
}

function renderSettings(saved) {
  settings = {...defaults,...saved};
  setCurrentLanguage(settings.language);
  selectSegment('protocol',settings.protocol);
  selectSegment('transport',settings.masqueTransport);
  $('socksAddress').value=settings.socksAddress;$('allowRemote').checked=settings.allowRemoteListener;$('peer').value=settings.peer;$('keepalive').value=settings.wgKeepalive;$('stallTimeout').value=settings.stallTimeout;$('watchdog').checked=settings.watchdog;$('quickReconnect').checked=settings.quickReconnect;$('configPath').value=settings.configPath;$('wgConfigPath').value=settings.wgConfigPath;$('masqueConfigPath').value=settings.masqueConfigPath;$('socks-display').textContent=settings.socksAddress;
  applyLanguage(settings.language, false);
}

async function changeLanguage(language, announce = true) {
  settings = readSettings();
  settings.language = language === 'fa' ? 'fa' : 'en';
  applyLanguage(settings.language, false);
  try {
    await invoke('save_settings',{settings});
    await invoke('set_language',{language:settings.language});
    if (announce) toast(t('toast.languageSaved'));
  } catch (error) { showError(error); }
}

function applyLanguage(language, rerenderSettings = true) {
  setCurrentLanguage(language);
  settings.language = getCurrentLanguage();
  $('language').value = settings.language;
  $('wizardEnglish').classList.toggle('selected',settings.language==='en');
  $('wizardPersian').classList.toggle('selected',settings.language==='fa');
  applyTranslations();
  if (rerenderSettings) renderChoiceOptions(); else { renderChoiceOptions(); }
  renderDocs($('docSearch').value);
  setState(state, activeEndpoint);
  renderWizard();
}

function queueSave() {
  settings=readSettings();$('socks-display').textContent=settings.socksAddress||'—';clearTimeout(saveTimer);saveTimer=setTimeout(()=>invoke('save_settings',{settings}).catch(showError),350);
}

function localizeError(error) {
  const raw=typeof error==='string'?error:error?.message||String(error);
  if(getCurrentLanguage()==='en') return raw;
  if(/address|IP address|port/i.test(raw)) return `${t('toast.invalidAddress')} (${raw})`;
  if(/in use|bind|os error 10048/i.test(raw)) return t('toast.portInUse');
  if(/connect|endpoint|tunnel|process exited/i.test(raw)) return t('toast.connectionFailed');
  return `${t('toast.error')} (${raw})`;
}
function showError(error){$('toast').textContent=localizeError(error);$('toast').className='show error';setTimeout(()=>{$('toast').className='';},5000)}
function toast(text){$('toast').textContent=text;$('toast').className='show';setTimeout(()=>{$('toast').className='';},2600)}

function setState(next, endpoint) {
  state=next;if(endpoint){activeEndpoint=endpoint;$('endpoint').textContent=endpoint}else if(next==='disconnected'){activeEndpoint='';$('endpoint').textContent=t('facts.notSelected')}
  const titleKeys={disconnected:'connection.readyTitle',scanning:'connection.scanningTitle',connecting:'connection.connectingTitle',connected:'connection.connectedTitle',reconnecting:'connection.reconnectingTitle',error:'connection.errorTitle'};
  const messageKeys={disconnected:'connection.readyMessage',scanning:'connection.scanningMessage',connecting:'connection.connectingMessage',connected:'connection.connectedMessage',reconnecting:'connection.reconnectingMessage',error:'connection.errorMessage'};
  const label=t(`status.${next}`);$('status').className=`status-pill ${next}`;$('status').querySelector('b').textContent=label;$('sidebar-state').textContent=label;$('sidebar-dot').className=`state-dot ${next}`;$('status-title').textContent=t(titleKeys[next]||titleKeys.error);$('status-message').textContent=t(messageKeys[next]||messageKeys.error);$('state-icon').textContent=next==='connected'?'✓':next==='error'?'!':next==='disconnected'?'↗':'…';
  const active=!['disconnected','error'].includes(next);$('connect').classList.toggle('hidden',active);$('disconnect').classList.toggle('hidden',!active);$('test').classList.toggle('hidden',next!=='connected');$('test').disabled=next!=='connected';$('diagnosticTest').disabled=next!=='connected';
}

function addLog(line){if(logs.length===0&&$('logs').textContent===t('diagnostics.waiting'))logs=[];logs.push(line);if(logs.length>2000)logs.splice(0,logs.length-2000);$('logs').textContent=logs.join('\n');$('logs').scrollTop=$('logs').scrollHeight}
async function connect(){try{showView('dashboard');settings=readSettings();await invoke('save_settings',{settings});logs=[];$('logs').textContent='';await invoke('connect',{settings})}catch(error){setState('error');showError(error)}}
async function disconnect(){try{await invoke('disconnect');setState('disconnected');$('elapsed').textContent='00:00:00'}catch(error){showError(error)}}
async function testConnection(){const buttons=[$('test'),$('diagnosticTest')];buttons.forEach(button=>button.disabled=true);try{const result=await invoke('connection_test',{settings:readSettings()});const list=$('trace');list.replaceChildren();for(const line of result.trim().split(/\r?\n/)){const[key,...rest]=line.split('=');const term=document.createElement('dt');const description=document.createElement('dd');term.textContent=key;description.textContent=rest.join('=');list.append(term,description)}$('testResult').classList.remove('hidden');showView('diagnostics');toast(t('toast.verified'))}catch(error){showError(error)}finally{buttons.forEach(button=>button.disabled=state!=='connected')}}

function showView(name){document.querySelectorAll('.view').forEach(view=>view.classList.toggle('active',view.id===`view-${name}`));document.querySelectorAll('.nav-item').forEach(item=>{const active=item.dataset.view===name;item.classList.toggle('active',active);if(active)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current')});window.scrollTo({top:0,behavior:'instant'})}

function renderDocs(query='') {
  const language=getCurrentLanguage();const normalized=query.trim().toLocaleLowerCase(language);const sections=docsByLanguage[language];const matches=sections.filter(section=>!normalized||`${section.title} ${section.html.replace(/<[^>]+>/g,' ')}`.toLocaleLowerCase(language).includes(normalized));const nav=$('docsNav');const content=$('docsContent');nav.replaceChildren();content.replaceChildren();
  matches.forEach((section,index)=>{const navButton=document.createElement('button');navButton.textContent=section.title;navButton.dataset.doc=section.id;navButton.addEventListener('click',()=>{const card=$(`doc-${section.id}`);card.open=true;card.scrollIntoView({behavior:'smooth',block:'start'});document.querySelectorAll('.docs-nav button').forEach(button=>button.classList.toggle('active',button===navButton))});nav.append(navButton);const card=document.createElement('details');card.id=`doc-${section.id}`;card.className='doc-card';card.open=index===0&&!normalized;const summary=document.createElement('summary');const number=document.createElement('span');number.textContent=String(sections.indexOf(section)+1).padStart(2,'0');const title=document.createElement('h3');title.textContent=section.title;summary.append(number,title);const body=document.createElement('div');body.className='doc-body';body.innerHTML=section.html;card.append(summary,body);content.append(card)});
  $('noDocResults').classList.toggle('hidden',matches.length!==0);
}

function showWizard(start=0){wizardIndex=start;$('welcomeWizard').classList.remove('hidden');renderWizard();$('closeWizard').focus()}
function closeWizard(complete=true){$('welcomeWizard').classList.add('hidden');if(complete)localStorage.setItem(wizardStorageKey,'1')}
function renderWizard(){if(!$('welcomeWizard'))return;document.querySelectorAll('.wizard-slide').forEach((slide,index)=>slide.classList.toggle('active',index===wizardIndex));document.querySelectorAll('.wizard-progress i').forEach((dot,index)=>dot.classList.toggle('active',index<=wizardIndex));$('wizardBack').disabled=wizardIndex===0;$('wizardNext').textContent=t(wizardIndex===4?'actions.start':'actions.next');$('wizardStep').textContent=`${wizardIndex+1} / 5`}

$('protocol').addEventListener('click',event=>{if(!event.target.dataset.value)return;selectSegment('protocol',event.target.dataset.value);settings.protocol=event.target.dataset.value;syncProtocol();queueSave()});
$('transport').addEventListener('click',event=>{if(!event.target.dataset.value)return;selectSegment('transport',event.target.dataset.value);queueSave()});
document.querySelector('.settings-panel').querySelectorAll('input,select').forEach(element=>element.addEventListener('change',queueSave));
$('language').addEventListener('change',event=>changeLanguage(event.target.value));
$('wizardEnglish').addEventListener('click',()=>changeLanguage('en',false));$('wizardPersian').addEventListener('click',()=>changeLanguage('fa',false));
$('connect').addEventListener('click',connect);$('disconnect').addEventListener('click',disconnect);$('test').addEventListener('click',testConnection);$('diagnosticTest').addEventListener('click',testConnection);
$('reset').addEventListener('click',()=>{const language=getCurrentLanguage();renderSettings({...defaults,language});queueSave();toast(t('toast.defaults'))});
$('copyProxy').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(readSettings().socksAddress);toast(t('toast.proxyCopied'))}catch(error){showError(error)}});
$('clearLogs').addEventListener('click',()=>{logs=[];$('logs').textContent=''});$('copyLogs').addEventListener('click',async()=>{try{await navigator.clipboard.writeText(logs.join('\n'));toast(t('toast.logsCopied'))}catch(error){showError(error)}});$('closeTest').addEventListener('click',()=>$('testResult').classList.add('hidden'));
$('docSearch').addEventListener('input',event=>renderDocs(event.target.value));document.querySelectorAll('.nav-item').forEach(item=>item.addEventListener('click',()=>showView(item.dataset.view)));
document.querySelectorAll('[data-external]').forEach(button=>button.addEventListener('click',async()=>{try{await openUrl(button.dataset.external)}catch(error){showError(error)}}));
$('showTour').addEventListener('click',()=>showWizard(0));$('closeWizard').addEventListener('click',()=>closeWizard(true));$('wizardBack').addEventListener('click',()=>{if(wizardIndex>0){wizardIndex-=1;renderWizard()}});$('wizardNext').addEventListener('click',()=>{if(wizardIndex<4){wizardIndex+=1;renderWizard()}else{closeWizard(true);showView('dashboard');$('connect').focus()}});$('welcomeWizard').addEventListener('click',event=>{if(event.target===$('welcomeWizard'))closeWizard(true)});document.addEventListener('keydown',event=>{if(event.key==='Escape'&&!$('welcomeWizard').classList.contains('hidden'))closeWizard(true)});

await listen('aether-log',event=>addLog(event.payload));await listen('aether-status',event=>setState(event.payload.state,event.payload.endpoint));await listen('tray-connect',connect);
try{renderSettings(await invoke('load_settings'))}catch(error){renderSettings(defaults);showError(error)}
setState('disconnected');if(!localStorage.getItem(wizardStorageKey))setTimeout(()=>showWizard(0),220);
setInterval(async()=>{if(state!=='connected')return;try{const seconds=await invoke('elapsed');const hours=String(Math.floor(seconds/3600)).padStart(2,'0');const minutes=String(Math.floor(seconds%3600/60)).padStart(2,'0');const remaining=String(seconds%60).padStart(2,'0');$('elapsed').textContent=`${hours}:${minutes}:${remaining}`}catch{}},1000);
