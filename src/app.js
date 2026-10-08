import {currentLanguage,setLanguage,t} from './i18n.js';
import {createMercuryOrb} from './mercury-orb.js';

// ---------------------------------------------------------------- constants
// Single source of truth for the protocol model comes from the backend
// (`protocol_catalog`); these mirrors exist only for browser-mode rendering and are
// asserted against the catalog at startup (they can never drift silently).
const PROTOCOL_DISPLAY_ORDER = ['smart','masque','wg','gool','mim','masque+psiphon','wg+psiphon','gool+psiphon','masque+tor','wg+tor','gool+tor'];
const PROTOCOL_LABELS = {
  en: {smart:'Smart Connect',masque:'MASQUE',wg:'WireGuard',gool:'gool',mim:'MIM',
       'masque+psiphon':'MASQUE + Psiphon','wg+psiphon':'WireGuard + Psiphon','gool+psiphon':'gool + Psiphon',
       'masque+tor':'MASQUE + Tor','wg+tor':'WireGuard + Tor','gool+tor':'gool + Tor'},
  fa: {smart:'اتصال هوشمند',masque:'MASQUE',wg:'WireGuard',gool:'gool',mim:'MIM',
       'masque+psiphon':'MASQUE + سایفون','wg+psiphon':'WireGuard + سایفون','gool+psiphon':'gool + سایفون',
       'masque+tor':'MASQUE + تور','wg+tor':'WireGuard + تور','gool+tor':'gool + تور'}
};
const PSIPHON_COUNTRIES = ['AT','BE','CH','CZ','DE','DK','ES','FR','GB','IE','IN','IT','JP','MD','NL','NO','PL','RO','RS','SE','SG','UA','US'];
const DEFAULT_PROTOCOL = 'wg+psiphon';
function baseProtocol(protocol){if(protocol.endsWith('+psiphon')||protocol.endsWith('+tor'))return protocol.split('+')[0];return protocol}
function privacyChain(protocol){if(protocol.endsWith('+psiphon'))return'psiphon';if(protocol.endsWith('+tor'))return'tor';return'none'}
function masqueTransportApplicable(protocol){const base=baseProtocol(protocol);return base==='masque'||base==='mim'}

const tauri=window.__TAURI__||{core:{invoke:async c=>c==='load_settings'?{}:c==='connection_state'?{state:'disconnected',elapsed:0,dailySeconds:0,dailyTime:'00:00:00'}:c==='protocol_catalog'?null:c==='installed_applications'?[]:c==='traffic_totals'?{uploaded:0,downloaded:0}:c==='daily_time'?[0,'00:00:00']:undefined},event:{listen:async()=>()=>{}},opener:{openUrl:async()=>{}},dialog:{open:async()=>null,save:async()=>null}};
const {invoke}=tauri.core,{listen}=tauri.event,openUrl=tauri.opener?.openUrl||tauri.opener?.open,$=id=>document.getElementById(id);

// ---------------------------------------------------------------- settings
const defaults={automaticUpdates:true,language:'en',appearance:'system',orbStyle:'living-mercury',connectionMode:'vpn',routingMode:'bypass-local',dnsLeakProtection:true,ipv6Behavior:'tunnel',killSwitch:false,tunMtu:1500,splitApplications:[],routeExclusions:[],protocol:DEFAULT_PROTOCOL,scanMode:'balanced',logLevel:'info',ipMode:'v4',obfuscation:'balanced',masqueTransport:'h2',goolMode:'masque',psiphonTransport:'auto',psiphonRegion:'',quicV2:true,socksAddress:'127.0.0.1:1819',allowRemoteListener:false,peer:'',mimOuterPeer:'',mimInnerPeer:'',wgKeepalive:5,stallTimeout:90,watchdog:true,configPath:'',wgConfigPath:'',masqueConfigPath:'',quickReconnect:true,autoConnectAtStart:false,ech:'',h2Fragment:false,h2FragmentSize:'16-32',h2FragmentDelay:'2-10',noDataCheck:false,validateSecs:10,startupSecs:30,reconnectSecs:2,wiwOuterPeer:'',wiwInnerPeer:'',team:'',accessEmail:'',accessToken:'',gateway:false,upstreamProxy:'',wgNoProfileRetry:false,routeSniff:true,routeSniffMs:400};
let settings={...defaults},state='disconnected',trafficTimer,pingTimer,locationTimer,timeTimer,reconnectTimer,reconnectAttempts=0,desiredConnected=false,operation=null,operationVersion=0,probeInFlight=false,pingInFlight=false,locationInFlight=false,telemetryGeneration=0,apps=[],selected=new Set(),showSystemApps=false,connectStartedAtMs=0,connectedTimelineRecorded=false,connectAttemptSequence=0,activeAttemptId='',initialized=false,pendingConnect=false,catalogChecked=false;
let mercuryOrb;

// ---------------------------------------------------------------- helpers
function frontendPhase(stage,result='success',errorCategory='',error=''){const timestampMs=Date.now(),monotonicMs=Math.round(performance.timeOrigin+performance.now());console.info('[timeline-ui]',JSON.stringify({timelineId:activeAttemptId,attemptId:activeAttemptId,sessionGeneration:operationVersion,protocol:settings.protocol,connectionMode:settings.connectionMode,stage,timestampMs,monotonicMs,elapsedMs:connectStartedAtMs?timestampMs-connectStartedAtMs:0,result,errorCategory,error:error?String(error?.message||error):''}))}
function selectSegment(id,value){$(id)?.querySelectorAll('button').forEach(b=>b.classList.toggle('active',b.dataset.value===value))}
function segmentValue(id){return $(id)?.querySelector('.active')?.dataset.value}
function setOptions(el,values,selectedValue){el.replaceChildren(...values.map(([value,label])=>{const o=document.createElement('option');o.value=value;o.textContent=label;o.selected=value===selectedValue;return o}))}
function formatNumber(value){return new Intl.NumberFormat(currentLanguage(),{minimumFractionDigits:1,maximumFractionDigits:1}).format(value)}
function formatBytes(value){if(!Number.isFinite(value)||value<0)return'--';if(value<1048576)return formatNumber(value/1024)+' KB';if(value<1073741824)return formatNumber(value/1048576)+' MB';return formatNumber(value/1073741824)+' GB'}
function formatLocation(value){const location=(value||'').trim();if(!/^[A-Z]{2}$/.test(location))return location;try{return new Intl.DisplayNames([currentLanguage()],{type:'region'}).of(location)||location}catch{return location}}
function countryFlag(code){if(!/^[A-Z]{2}$/.test(code))return'🌐';return String.fromCodePoint(...[...code].map(c=>0x1F1E6+c.charCodeAt(0)-65))}

// ---------------------------------------------------------------- protocol rendering
function renderProtocolOptions(){
  const lang=currentLanguage()==='fa'?'fa':'en';
  setOptions($('protocol'),PROTOCOL_DISPLAY_ORDER.map(p=>[p,PROTOCOL_LABELS[lang][p]||p]),settings.protocol);
}
function syncProtocol(){
  const protocol=$('protocol').value||settings.protocol;
  // Combined privacy entries are chain-mode features; hidden (not lost) in Proxy mode.
  const proxyMode=segmentValue('connectionMode')==='manual';
  [...$('protocol').options].forEach(o=>{o.hidden=proxyMode&&(o.value.endsWith('+psiphon')||o.value.endsWith('+tor'))});
  if(proxyMode&&privacyChain(protocol)==='none'&&![...$('protocol').options].find(o=>o.value===protocol&&!o.hidden)){$('protocol').value='smart'}
  // MASQUE connection method: visible where the finalized Android mapping applies.
  const masqueLike=masqueTransportApplicable(protocol);
  $('transportField').classList.toggle('hidden',!masqueLike);
  $('quicV2Field').classList.toggle('hidden',!masqueLike);
  // gool topology (Core v2.3.0): visible for the gool family; the two topologies are
  // materially different routing paths the user chooses between (dev.032 §2.4).
  const goolLike=baseProtocol(protocol)==='gool';
  $('goolModeField').classList.toggle('hidden',!goolLike);
  $('mimPeerFields').classList.toggle('hidden',protocol!=='mim');
  $('peerField').classList.toggle('hidden',protocol==='mim');
  // Psiphon controls: only for +Psiphon protocols.
  const chain=privacyChain(protocol);
  $('psiphonTransportField').classList.toggle('hidden',chain!=='psiphon');
  $('psiphonLocationField').classList.toggle('hidden',chain!=='psiphon');
  // Home location selector: always-visible compact selector for +Psiphon (two-way synced
  // with Configurations); read-only value for others.
  const editable=chain==='psiphon'&&!proxyMode;
  $('homeCountry').classList.toggle('hidden',!editable);
  $('locationValue').classList.toggle('hidden',editable);
  renderHomeCountryClosed();
  syncAdvanced();
}
function renderHomeCountryClosed(){
  const region=(settings.psiphonRegion||'').trim().toUpperCase();
  $('homeCountryValue').textContent=region?`${countryFlag(region)} ${region}`:`🌐 ${t('location.auto')}`;
}
function renderPsiphonLocations(){
  const values=[['',`🌐 ${t('location.autoFull')}`],...PSIPHON_COUNTRIES.map(code=>{let name=code;try{name=new Intl.DisplayNames([currentLanguage()],{type:'region'}).of(code)||code}catch{}return[code,`${countryFlag(code)} ${name}`]})];
  setOptions($('psiphonLocation'),values,(settings.psiphonRegion||'').toUpperCase());
}
function renderCountryPopup(){
  const active=(settings.psiphonRegion||'').trim().toUpperCase();
  const rows=[['',`🌐 ${t('location.auto')}`],...PSIPHON_COUNTRIES.map(code=>{let name=code;try{name=new Intl.DisplayNames([currentLanguage()],{type:'region'}).of(code)||code}catch{}return[code,`${countryFlag(code)} ${name}`]})];
  $('countryList').replaceChildren(...rows.map(([code,label])=>{const row=document.createElement('div');row.className='country-row'+(code===active?' active':'');row.tabIndex=0;row.dataset.code=code;row.textContent=label;row.onclick=()=>selectHomeCountry(code);return row}));
}
function selectHomeCountry(code){settings.psiphonRegion=code;$('psiphonLocation').value=code;renderCountryPopup();renderHomeCountryClosed();queueSave()}
// Anchored popup (PROMPT §9): opens UPWARD above the LOCATION row, clamped so it is
// always fully visible inside the window; compact, RTL-aware, scrollable. The dev.027
// popup had no coordinates and rendered at its document-flow position at the bottom of
// the Home content, overflowing the window (the reported "disappears outside window").
function positionCountryPopup(){
  const popup=$('countryPopup'),row=$('locationRow');
  if(popup.classList.contains('hidden')||!row)return;
  const anchor=row.getBoundingClientRect(),rect=popup.getBoundingClientRect();
  const margin=8;
  let width=Math.min(Math.max(rect.width,170),Math.min(230,window.innerWidth-24));
  let left=anchor.left+(anchor.width-width)/2;
  left=Math.min(Math.max(margin,left),window.innerWidth-width-margin);
  let height=Math.min(rect.height,Math.min(280,window.innerHeight*0.42));
  let top=anchor.top-height-margin;
  if(top<margin){ // genuinely not enough room above (extremely short window): clamp top,
    // keep the popup fully visible — never let it extend past the window bottom.
    top=margin;
  }
  popup.style.left=`${Math.round(left)}px`;
  popup.style.top=`${Math.round(top)}px`;
  popup.style.width=`${Math.round(width)}px`;
}
function toggleCountryPopup(open){
  const popup=$('countryPopup');
  popup.classList.toggle('hidden',!open);
  $('homeCountry')?.setAttribute('aria-expanded',String(!!open));
  if(open){renderCountryPopup();requestAnimationFrame(positionCountryPopup)}
}

// ---------------------------------------------------------------- accordion (single-open, no persistence)
function closeAllSections(){document.querySelectorAll('.accordion-section').forEach(s=>s.classList.remove('open'))}
function openSection(name){closeAllSections();document.querySelector(`.accordion-section[data-section="${name}"]`)?.classList.add('open')}
function toggleSection(name){const section=document.querySelector(`.accordion-section[data-section="${name}"]`);if(!section)return;const wasOpen=section.classList.contains('open');closeAllSections();if(!wasOpen)section.classList.add('open')}

// ---------------------------------------------------------------- options/settings rendering
function renderOptions(){
  setOptions($('scanMode'),[['turbo',t('scan.turbo')],['balanced',t('scan.balanced')],['thorough',t('scan.thorough')],['stealth',t('scan.stealth')],['ironclad',t('scan.ironclad')]],settings.scanMode);
  renderProtocolOptions();renderPsiphonLocations();syncProtocol();
}
function syncAdvanced(){const h2=segmentValue('transport')==='h2'||settings.masqueTransport==='h2';$('h2FragmentFields').classList.toggle('hidden',!h2||!$('h2Fragment').checked);$('echCustomLabel').classList.toggle('hidden',$('ech').value!=='custom');$('echCustom').classList.toggle('hidden',$('ech').value!=='custom')}
function echControlValue(){const mode=$('ech').value;return mode==='custom'?$('echCustom').value.trim():mode}
function renderEch(value){const saved=(value||'').trim(),mode=saved==='auto'||saved===''?saved:'custom';$('ech').value=mode;$('echCustom').value=mode==='custom'?saved:''}
function updateSelectedCount(){$('selectedCount').textContent=new Intl.NumberFormat(currentLanguage()).format(selected.size)+' '+t('picker.selected')}
function syncSplit(){const on=$('splitEnabled').checked;$('splitControls').classList.toggle('hidden',!on);settings.routingMode=on?(segmentValue('splitMode')||'split-include'):'bypass-local';updateSelectedCount()}
function readSettings(){return{...settings,language:currentLanguage(),appearance:$('appearance').value,orbStyle:$('orbStyle').value,automaticUpdates:$('automaticUpdates').checked,autoConnectAtStart:settings.autoConnectAtStart,connectionMode:segmentValue('connectionMode'),routingMode:settings.routingMode,protocol:$('protocol').value||settings.protocol,masqueTransport:segmentValue('transport')||settings.masqueTransport,goolMode:segmentValue('goolMode')||settings.goolMode||'masque',psiphonTransport:$('psiphonTransport').value||'auto',psiphonRegion:$('psiphonLocation')?.value??settings.psiphonRegion,quicV2:$('quicV2').checked,scanMode:$('scanMode').value,ipv6Behavior:$('ipv6Behavior').value,tunMtu:parseInt($('tunMtu').value)||1500,dnsLeakProtection:$('dnsLeakProtection').checked,killSwitch:$('killSwitch').checked,quickReconnect:$('quickReconnect').checked,allowRemoteListener:$('allowRemoteListener').checked,peer:$('peer').value.trim(),mimOuterPeer:$('mimOuterPeer').value.trim(),mimInnerPeer:$('mimInnerPeer').value.trim(),ech:echControlValue(),h2Fragment:$('h2Fragment').checked,h2FragmentSize:$('h2FragmentSize').value.trim(),h2FragmentDelay:$('h2FragmentDelay').value.trim(),noDataCheck:$('noDataCheck').checked,validateSecs:parseInt($('validateSecs').value)||10,startupSecs:parseInt($('startupSecs').value)||30,reconnectSecs:parseInt($('reconnectSecs').value)||2,wiwOuterPeer:$('wiwOuterPeer').value.trim(),wiwInnerPeer:$('wiwInnerPeer').value.trim(),team:$('team').value.trim(),accessEmail:$('accessEmail').value.trim(),accessToken:$('accessToken').value.trim(),gateway:$('gateway').checked,upstreamProxy:$('upstreamProxy').value.trim(),wgNoProfileRetry:$('wgNoProfileRetry').checked,routeSniff:$('routeSniff').checked,routeSniffMs:parseInt($('routeSniffMs').value)||400,splitApplications:[...selected]}}
function queueSave(){settings=readSettings();clearTimeout(queueSave.timer);queueSave.timer=setTimeout(()=>invoke('save_settings',{settings}).catch(showError),250)}
async function refreshLanStatus(){try{const lan=await invoke('lan_status',{settings:readSettings()});$('lanAddressValue').textContent=lan.enabled&&lan.address?`${lan.address}:${lan.port}`:t('config.lanDisabled')}catch{$('lanAddressValue').textContent=t('config.lanUnavailable')}}
function applyOrbStyle(){const selectedStyle=$('orbStyle')?.value==='living-mercury'?'living-mercury':'classic';if(mercuryOrb){mercuryOrb.setStyle(selectedStyle);mercuryOrb.setState(state);mercuryOrb.setTheme(settings.appearance)}$('connectionOrb')?.classList.toggle('living-mercury',selectedStyle==='living-mercury');$('mercuryVisual')?.classList.toggle('active',selectedStyle==='living-mercury')}
function renderSettings(saved){settings={...defaults,...saved};settings.normalize=null;setLanguage(settings.language);selectSegment('connectionMode',settings.connectionMode);$('protocol').value=settings.protocol;selectSegment('transport',settings.masqueTransport);selectSegment('goolMode',['classic','masque'].includes(settings.goolMode)?settings.goolMode:'masque');$('psiphonTransport').value=settings.psiphonTransport||'auto';selectSegment('splitMode',settings.routingMode==='split-exclude'?'split-exclude':'split-include');selected=new Set(settings.splitApplications||[]);$('splitEnabled').checked=['split-include','split-exclude'].includes(settings.routingMode);$('peer').value=settings.peer||'';$('mimOuterPeer').value=settings.mimOuterPeer||'';$('mimInnerPeer').value=settings.mimInnerPeer||'';$('quicV2').checked=settings.quicV2;$('dnsLeakProtection').checked=settings.dnsLeakProtection;$('killSwitch').checked=settings.killSwitch;$('quickReconnect').checked=settings.quickReconnect;$('allowRemoteListener').checked=settings.allowRemoteListener;$('automaticUpdates').checked=settings.automaticUpdates;$('appearance').value=settings.appearance;$('orbStyle').value=settings.orbStyle;$('language').value=settings.language;$('ipv6Behavior').value=settings.ipv6Behavior||'tunnel';$('tunMtu').value=settings.tunMtu||1500;renderEch(settings.ech);$('h2Fragment').checked=settings.h2Fragment;$('h2FragmentSize').value=settings.h2FragmentSize||'16-32';$('h2FragmentDelay').value=settings.h2FragmentDelay||'2-10';$('noDataCheck').checked=settings.noDataCheck;$('validateSecs').value=settings.validateSecs||10;$('startupSecs').value=settings.startupSecs||30;$('reconnectSecs').value=settings.reconnectSecs||2;$('wiwOuterPeer').value=settings.wiwOuterPeer||'';$('wiwInnerPeer').value=settings.wiwInnerPeer||'';$('team').value=settings.team||'';$('accessEmail').value=settings.accessEmail||'';$('accessToken').value=settings.accessToken||'';$('gateway').checked=settings.gateway;$('upstreamProxy').value=settings.upstreamProxy||'';$('wgNoProfileRetry').checked=settings.wgNoProfileRetry;$('routeSniff').checked=settings.routeSniff;$('routeSniffMs').value=settings.routeSniffMs||400;document.body.dataset.theme=settings.appearance;renderOptions();renderHomeCountryClosed();applyOrbStyle();refreshLanStatus()}

// ---------------------------------------------------------------- navigation + state
function openDrawer(){$('drawer').classList.add('open');$('drawerScrim').classList.add('open')}
function closeDrawer(){$('drawer').classList.remove('open');$('drawerScrim').classList.remove('open');toggleCountryPopup(false)}
function showView(name){document.querySelectorAll('.view').forEach(v=>v.classList.toggle('active',v.id==='view-'+name));document.querySelectorAll('.nav-item').forEach(v=>v.classList.toggle('active',v.dataset.view===name));$('pageTitle').textContent=name==='home'?'Aethon VPN':t('nav.'+name);closeDrawer();toggleCountryPopup(false);window.scrollTo({top:0,behavior:'instant'});if(name==='configurations')closeAllSections();if(name==='diagnostics')refreshDiagnostics()}function setState(next){state=next;mercuryOrb?.setState(next);const active=!['disconnected','error'].includes(next);$('connectionOrb').className='connection-orb '+next+($('orbStyle')?.value==='living-mercury'?' living-mercury':'');$('statusPill').className='status-pill '+next;$('orbLabel').textContent=next==='connected'?t('actions.disconnect'):next==='disconnecting'?t('status.disconnecting'):active?t('status.connecting'):t('actions.connect');$('statusTitle').textContent=t('status.'+next);const key=next==='connected'?'connected':next==='reconnecting'?'reconnecting':next==='disconnecting'?'disconnecting':next==='disconnected'?'ready':next==='scanning'||next==='connecting'?'connecting':'error';$('statusMessage')?.replaceChildren(document.createTextNode(t('connection.'+key)));$('drawerState').textContent=t('status.'+next);
  // TIME row: visible only while Connected (daily cumulative time, not session duration).
  $('timeRow').classList.toggle('hidden',next!=='connected');
  if(next==='connected'&&!connectedTimelineRecorded&&connectStartedAtMs){connectedTimelineRecorded=true;frontendPhase('t20_ui_connected');startTelemetry()}
  if(next==='disconnected'||next==='error'){stopTelemetry();$('uploadTraffic').textContent='--';$('downloadTraffic').textContent='--';$('pingValue').textContent='--';$('locationValue').textContent='\u2014';$('timeValue').textContent='00:00:00'}else if(next==='reconnecting'){stopTelemetry();$('pingValue').textContent='--';$('locationValue').textContent=t('traffic.locationDetecting')}else if(next!=='connected'){$('pingValue').textContent='--';$('locationValue').textContent=t('traffic.locationDetecting')}}
function showError(error){$('toast').textContent=typeof error==='string'?error:error?.message||String(error);$('toast').className='show error';setTimeout(()=>$('toast').className='',5000)}
function toast(message){$('toast').textContent=message;$('toast').className='show';setTimeout(()=>$('toast').className='',2600)}

// ---------------------------------------------------------------- connect/disconnect (single-flight, versioned)
async function connect(){if(!initialized){pendingConnect=true;return}if(operation||!['disconnected','error'].includes(state))return;connectStartedAtMs=Date.now();connectedTimelineRecorded=false;const clientStartedAtMs=connectStartedAtMs,version=++operationVersion;activeAttemptId=`ui-${clientStartedAtMs}-${++connectAttemptSequence}`;let retryAfterFailure=false;operation='connect';desiredConnected=true;settings=readSettings();frontendPhase('frontend_connect_start');setState('connecting');frontendPhase('backend_connect_ipc_start');const backendConnect=invoke('connect',{settings,clientStartedAtMs,attemptId:activeAttemptId});frontendPhase('settings_save_start');const settingsSave=invoke('save_settings',{settings}).then(()=>frontendPhase('settings_save_success')).catch(error=>{frontendPhase('settings_save_failure','failure','settings_persistence',error);console.warn('[settings-save]',error)});setState('scanning');try{await backendConnect;frontendPhase('backend_connect_ipc_success');if(version===operationVersion){reconnectAttempts=0;const snapshot=await invoke('connection_state');if(snapshot.state==='disconnected'){desiredConnected=false;setState('disconnected')}}}catch(error){frontendPhase('backend_connect_ipc_failure','failure','backend_connect',error);if(version===operationVersion){setState('error');showError(error);retryAfterFailure=desiredConnected&&settings.quickReconnect}}finally{void settingsSave;if(version===operationVersion){operation=null;if(retryAfterFailure)scheduleReconnect()}}}
async function disconnect(){if(operation==='disconnect'||state==='disconnecting'||state==='disconnected')return;const version=++operationVersion;operation='disconnect';desiredConnected=false;clearTimeout(reconnectTimer);reconnectTimer=null;frontendPhase('disconnect_clicked');stopTelemetry();setState('disconnecting');frontendPhase('disconnecting_visible');try{await invoke('disconnect')}catch(error){if(version===operationVersion)showError(error)}finally{if(version===operationVersion){frontendPhase('disconnect_complete');operation=null;setState('disconnected')}}}
function scheduleReconnect(){if(!desiredConnected||!settings.quickReconnect||reconnectAttempts>=5||reconnectTimer||operation)return false;reconnectAttempts++;setState('reconnecting');reconnectTimer=setTimeout(async()=>{reconnectTimer=null;if(!desiredConnected||operation)return;const version=++operationVersion;operation='disconnect';try{await invoke('disconnect')}finally{if(version===operationVersion){operation=null;if(desiredConnected){setState('disconnected');connect()}}}},Math.min(20000,1500*2**(reconnectAttempts-1)));return true}

// ---------------------------------------------------------------- telemetry (traffic / ping / location / TIME)
function stopTelemetry(){telemetryGeneration++;clearInterval(trafficTimer);clearInterval(pingTimer);clearInterval(locationTimer);clearInterval(timeTimer);trafficTimer=pingTimer=locationTimer=timeTimer=null;probeInFlight=pingInFlight=false;locationInFlight=false}
function startTelemetry(){stopTelemetry();const generation=telemetryGeneration;
  const traffic=async()=>{try{const m=await invoke('traffic_totals');if(generation!==telemetryGeneration||state!=='connected')return;$('uploadTraffic').textContent=formatBytes(m.uploaded);$('downloadTraffic').textContent=formatBytes(m.downloaded)}catch{}};
  const ping=async()=>{if(probeInFlight||pingInFlight||generation!==telemetryGeneration||state!=='connected')return;probeInFlight=pingInFlight=true;frontendPhase('ping_probe_started');try{const value=await invoke('vpn_ping',{settings});if(generation!==telemetryGeneration||state!=='connected')return;if(value!=null){$('pingValue').textContent=new Intl.NumberFormat(currentLanguage()).format(value)+' ms';frontendPhase('ping_available')}}catch(error){if(generation===telemetryGeneration&&state==='connected')$('pingValue').textContent='--';console.info('[ping]',error)}finally{probeInFlight=pingInFlight=false}};
  const location=async()=>{if(locationInFlight||generation!==telemetryGeneration||state!=='connected')return;locationInFlight=true;frontendPhase('location_probe_started');try{const value=await invoke('vpn_location',{settings});if(generation!==telemetryGeneration||state!=='connected')return;$('locationValue').textContent=formatLocation(value)||t('traffic.locationUnavailable');frontendPhase('location_available')}catch(error){if(generation===telemetryGeneration&&state==='connected')$('locationValue').textContent=t('traffic.locationUnavailable');console.info('[location]',error)}finally{locationInFlight=false}};
  const timeTick=async()=>{if(generation!==telemetryGeneration||state!=='connected')return;try{const[seconds,rendered]=await invoke('daily_time');if(generation!==telemetryGeneration||state!=='connected')return;$('timeValue').textContent=rendered||secondsToHms(seconds)}catch{const seconds=await invoke('connection_state').then(s=>s.dailySeconds).catch(()=>null);if(seconds!=null&&(generation===telemetryGeneration)&&state==='connected')$('timeValue').textContent=secondsToHms(seconds)}};
  traffic();void ping();void location();void timeTick();
  trafficTimer=setInterval(traffic,1000);pingTimer=setInterval(ping,15000);locationTimer=setInterval(location,30000);timeTimer=setInterval(timeTick,1000)}
function secondsToHms(seconds){const s=Math.max(0,seconds|0);return `${String(Math.floor(s/3600)).padStart(2,'0')}:${String(Math.floor(s%3600/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}`}

// ---------------------------------------------------------------- telegram / updates / diagnostics
async function openTelegram(){try{await openUrl('tg://resolve?domain=hamvex')}catch{try{await openUrl('https://t.me/hamvex')}catch{toast(t('toast.telegram'))}}}
async function checkUpdates(manual=false){$('checkUpdates').disabled=true;if(manual)$('updateStatus').textContent=t('updates.checking');try{const info=await invoke('check_for_update');$('currentVersion').textContent=info.currentVersion||'2.2.0';$('updateBadge').classList.toggle('hidden',!info.available);if(info.available){$('updateStatus').textContent=t('updates.available');$('updateAction').classList.remove('hidden');$('updateAction').textContent=t('updates.download')}else if(manual){$('updateStatus').textContent=t('updates.upToDate');toast(t('toast.updated'))}}catch(error){if(manual){$('updateStatus').textContent=t('updates.failed');showError(error)}}finally{$('checkUpdates').disabled=false}}
async function updateAction(){try{if($('updateAction').textContent===t('updates.install')){await invoke('install_update');return}$('updateProgress').classList.remove('hidden');await invoke('download_update');$('updateAction').textContent=t('updates.install');$('updateStatus').textContent=t('updates.ready')}catch(error){showError(error)}}
async function refreshDiagnostics(){try{const d=await invoke('network_diagnostics',{settings:readSettings()});$('diagProtocol').textContent=d.protocol||'--';$('diagTun').textContent=d.tunInterface||'--';$('diagMtu').textContent=d.runtimeMtu||'--';$('diagExitIp').textContent=d.exitIp||'--';$('diagPing').textContent=d.pingMs==null?'--':d.pingMs+' ms';$('diagTraffic').textContent='↓ '+formatBytes(d.downloadBytes)+' / ↑ '+formatBytes(d.uploadBytes);$('diagState').textContent=d.tunnelState||'--';$('diagAetherVersion').textContent=d.aetherVersion||'--';$('diagProcesses').textContent=(d.aetherRunning?'running':'stopped')+' / '+(d.xrayRunning?'running':'stopped')}catch(error){showError(error)}}
async function refreshAboutCore(){try{$('aboutCoreVersion').textContent=await invoke('verified_core_version')}catch{$('aboutCoreVersion').textContent='--'}}

// ---------------------------------------------------------------- split tunneling picker
function renderApps(){const query=$('appSearch').value.toLowerCase();const visible=apps.filter(a=>(showSystemApps||!a.system)&&(a.name+' '+a.path).toLowerCase().includes(query));$('appList').replaceChildren(...visible.map(app=>{const row=document.createElement('div');row.className='app-row'+(app.missing?' missing':'');row.innerHTML='<span class="app-icon">EXE</span><span><b></b><small></small></span><input type="checkbox">';if(app.icon){const image=document.createElement('img');image.className='app-icon';image.alt='';image.src='data:image/png;base64,'+app.icon;row.querySelector('.app-icon').replaceWith(image)}row.querySelector('b').textContent=app.name+(app.system?' ·':'');row.querySelector('small').textContent=app.missing?t('split.missing'):app.path;row.querySelector('input').checked=selected.has(app.path);row.querySelector('input').disabled=!!app.protected;row.onclick=e=>{if(app.protected||e.target.tagName==='INPUT')return;row.querySelector('input').checked=!row.querySelector('input').checked;row.querySelector('input').checked?selected.add(app.path):selected.delete(app.path);updateSelectedCount()};return row}));if(!visible.length){const empty=document.createElement('div');empty.className='empty-row';empty.textContent=t('picker.empty');$('appList').replaceChildren(empty)}}
async function openApps(){try{apps=await invoke('installed_applications');for(const path of selected)if(!apps.some(a=>a.path===path))apps.push({name:path.split(/[\\/]/).pop(),path,icon:'',missing:true});renderApps();$('appDialog').classList.remove('hidden')}catch(error){showError(error)}}
function closeApps(){$('appDialog').classList.add('hidden')}

// ---------------------------------------------------------------- backup / restore
async function exportBackup(){try{const target=await tauri.dialog.save({filters:[{name:'Aethon Configuration',extensions:['json']}],defaultPath:'aethon-configuration.json'});if(!target)return;const payload=JSON.stringify({...readSettings(),_format:'aethon-windows-backup',_version:2},null,2);await invoke('write_backup_file',{path:target,content:payload});toast(t('toast.backupSaved'))}catch(error){showError(error)}}
async function importBackup(){try{const picked=await tauri.dialog.open({multiple:false,filters:[{name:'Aethon Configuration',extensions:['json']}]});if(!picked)return;const content=await invoke('read_backup_file',{path:picked});let parsed;try{parsed=JSON.parse(content)}catch{toast(t('toast.backupInvalid'));return}if(!parsed||typeof parsed!=='object'||!parsed.protocol){toast(t('toast.backupInvalid'));return}renderSettings({...defaults,...parsed});queueSave();toast(t('toast.backupLoaded'))}catch(error){showError(error)}}

// ---------------------------------------------------------------- catalog cross-check
async function verifyProtocolCatalog(){try{const catalog=await invoke('protocol_catalog');if(!catalog)return;catalogChecked=true;const backendOrder=catalog.displayOrder||[];if(backendOrder.length&&JSON.stringify(backendOrder)!==JSON.stringify(PROTOCOL_DISPLAY_ORDER)){console.warn('[protocol-catalog] frontend/backend display order drift detected',backendOrder)}if(catalog.default&&catalog.default!==DEFAULT_PROTOCOL){console.warn('[protocol-catalog] default protocol drift detected',catalog.default)}}catch{ /* browser mode: no catalog */ }}

// ---------------------------------------------------------------- boot
mercuryOrb=createMercuryOrb($('mercuryCanvas'),{onFallback:reason=>{settings.orbStyle='classic';if($('orbStyle'))$('orbStyle').value='classic';$('connectionOrb')?.classList.remove('living-mercury');$('mercuryVisual')?.classList.remove('active');console.info('[mercury-orb] classic fallback',reason)}});
renderSettings(defaults);
$('menuButton').onclick=openDrawer;$('drawerScrim').onclick=closeDrawer;$('telegramButton').onclick=openTelegram;$('aboutTelegram').onclick=openTelegram;document.querySelectorAll('.nav-item').forEach(b=>b.onclick=()=>showView(b.dataset.view));
$('connectionOrb').onclick=()=>state==='disconnecting'?undefined:['connected','scanning','connecting','reconnecting'].includes(state)?disconnect():connect();
$('connectionMode').onclick=e=>{if(e.target.dataset.value){selectSegment('connectionMode',e.target.dataset.value);syncProtocol();queueSave()}};
$('protocol').onchange=()=>{settings.protocol=$('protocol').value;syncProtocol();queueSave()};
$('transport').onclick=e=>{if(e.target.dataset.value){selectSegment('transport',e.target.dataset.value);syncAdvanced();queueSave()}};
$('goolMode').onclick=e=>{if(e.target.dataset.value){selectSegment('goolMode',e.target.dataset.value);queueSave()}};
$('psiphonTransport').onchange=()=>queueSave();
$('psiphonLocation').onchange=()=>{settings.psiphonRegion=$('psiphonLocation').value;renderHomeCountryClosed();renderCountryPopup();queueSave()};
$('splitMode').onclick=e=>{if(e.target.dataset.value){selectSegment('splitMode',e.target.dataset.value);syncSplit();queueSave()}};$('splitEnabled').onchange=()=>{syncSplit();queueSave()};
// Accordion: one-open-only; Help obeys the same rule; clicking the open section closes it.
document.querySelectorAll('.accordion-header').forEach(header=>header.onclick=()=>toggleSection(header.parentElement.dataset.section));
// Home country popup: compact, content-conscious, anchored ABOVE the LOCATION row
// (PROMPT §9 — upward, never downward, always fully visible).
$('homeCountry').onclick=e=>{e.stopPropagation();toggleCountryPopup($('countryPopup').classList.contains('hidden'))};
document.addEventListener('click',e=>{if(!$('countryPopup').classList.contains('hidden')&&!$('countryPopup').contains(e.target)&&e.target!==$('homeCountry'))toggleCountryPopup(false)});
window.addEventListener('resize',()=>{if(!$('countryPopup').classList.contains('hidden'))requestAnimationFrame(positionCountryPopup)});
// Reset Defaults: after Help in Configurations, confirmation required.
$('resetSettings').onclick=()=>{$('resetDialog').classList.remove('hidden')};
$('resetCancel').onclick=()=>{$('resetDialog').classList.add('hidden')};
$('resetConfirm').onclick=()=>{$('resetDialog').classList.add('hidden');const keep={language:settings.language,appearance:settings.appearance,orbStyle:settings.orbStyle,automaticUpdates:settings.automaticUpdates};renderSettings({...defaults,...keep});queueSave()};
$('openApps').onclick=openApps;$('closeApps').onclick=closeApps;$('applyApps').onclick=()=>{closeApps();queueSave()};$('appSearch').oninput=renderApps;
$('openDiagnostics').onclick=()=>showView('diagnostics');
$('showSystemApps').onchange=e=>{showSystemApps=e.target.checked;renderApps()};
$('selectAll').onclick=()=>{apps.forEach(a=>{if(!a.protected)selected.add(a.path)});renderApps();updateSelectedCount()};$('clearAll').onclick=()=>{selected.clear();renderApps();updateSelectedCount()};
$('exportBackup').onclick=exportBackup;$('importBackup').onclick=importBackup;
document.querySelectorAll('input,select').forEach(e=>e.addEventListener('change',()=>{if(e.id==='language'){settings.language=e.value;setLanguage(e.value);renderOptions();renderPsiphonLocations();renderCountryPopup();renderHomeCountryClosed();setState(state);updateSelectedCount()}if(e.id==='appearance'){document.body.dataset.theme=e.value;mercuryOrb?.setTheme(e.value)}if(e.id==='orbStyle')applyOrbStyle();if(e.id==='ech')syncAdvanced();if(e.id==='h2Fragment')syncAdvanced();if(e.id==='allowRemoteListener')refreshLanStatus();queueSave()}));
$('manageNotifications').onclick=()=>openUrl('ms-settings:notifications').catch(showError);
$('addExecutable').onclick=async()=>{try{const path=await tauri.dialog.open({multiple:false,filters:[{name:'Executable',extensions:['exe']}]});if(path){selected.add(path);if(!apps.some(a=>a.path===path))apps.push({name:path.split(/[\\/]/).pop(),path,icon:''});renderApps();toast(t('picker.added'))}}catch(error){showError(error)}};
$('checkUpdates').onclick=()=>checkUpdates(true);$('updateAction').onclick=updateAction;document.querySelectorAll('[data-external]').forEach(b=>b.onclick=()=>openUrl(b.dataset.external).catch(showError));
$('refreshDiagnostics').onclick=refreshDiagnostics;
await listen('aether-log',e=>console.info('[Aether]',e.payload));await listen('aether-status',e=>{if(operation==='disconnect'&&e.payload.state!=='disconnected')return;if(!desiredConnected&&!['disconnected','error'].includes(e.payload.state))return;if(e.payload.state==='error'&&scheduleReconnect())return;setState(e.payload.state);if(e.payload.state==='connected'){startTelemetry();/* dev.033 topology truthfulness: the backend's connected message states the gool topology actually running (and calls out a compatibility fallback); show it under the title instead of dropping it. */if(e.payload.message)$('statusMessage')?.replaceChildren(document.createTextNode(e.payload.message))}});await listen('routing-status',e=>{if(operation==='disconnect'||!desiredConnected)return;if(e.payload.state==='reconnecting')setState('reconnecting');if(e.payload.state==='error'&&!scheduleReconnect())setState('error');if(e.payload.state==='connected'){reconnectAttempts=0;startTelemetry()}});await listen('update-progress',e=>{if(e.payload.percent!=null){$('updateProgress').classList.remove('hidden');$('updateProgress').value=e.payload.percent}});await listen('tray-connect',connect);
document.addEventListener('keydown',e=>{if(e.key==='Escape'){if(!$('appDialog').classList.contains('hidden'))closeApps();else if(!$('resetDialog').classList.contains('hidden'))$('resetDialog').classList.add('hidden');else if(!$('countryPopup').classList.contains('hidden'))toggleCountryPopup(false);else closeDrawer()}if(e.key==='Backspace'&&!['INPUT','SELECT'].includes(document.activeElement.tagName)&&!$('view-home').classList.contains('active'))showView('home')});
try{renderSettings(await invoke('load_settings'))}catch{renderSettings(defaults)}
try{const snapshot=await invoke('connection_state');desiredConnected=snapshot.state!=='disconnected';setState(snapshot.state);if(snapshot.state==='connected'){startTelemetry();if(snapshot.dailyTime)$('timeValue').textContent=snapshot.dailyTime}}catch{setState('disconnected')}
void verifyProtocolCatalog();void refreshAboutCore();
initialized=true;if(pendingConnect&&state==='disconnected'){pendingConnect=false;setTimeout(()=>connect(),0)}else if(state==='disconnected'&&settings.autoConnectAtStart)setTimeout(()=>connect(),800);if(settings.automaticUpdates)setTimeout(()=>checkUpdates(false),1200);setInterval(()=>{if(settings.automaticUpdates)checkUpdates(false)},12*60*60*1000);
