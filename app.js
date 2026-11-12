(() => {
  'use strict';
  const $ = id => document.getElementById(id);

  // ====================================================================
  // Nastavení
  // ====================================================================
  const RADAR_MIN_ZOOM = 9, SPEED_MIN_ZOOM = 13, POI_MIN_ZOOM = 11;
  const RADAR_WARN_M = 600, ROAD_SNAP_M = 35, OFF_ROUTE_M = 80, CAM_ON_ROUTE_M = 40;
  const OVERPASS = ['https://overpass-api.de/api/interpreter', 'https://overpass.kumi.systems/api/interpreter'];
  const ROUTERS = {
    car:  ['https://routing.openstreetmap.de/routed-car/route/v1/driving/', 'https://router.project-osrm.org/route/v1/driving/'],
    bike: ['https://routing.openstreetmap.de/routed-bike/route/v1/driving/'],
    foot: ['https://routing.openstreetmap.de/routed-foot/route/v1/driving/'],
  };
  const MODES = { car: '🚗 Auto', bike: '🚲 Kolo', foot: '🚶 Pěšky' };
  const BUCKETS = [
    { max: 30,  color: '#34d399', label: 'do 30 km/h' },
    { max: 50,  color: '#fde047', label: '40–50 km/h' },
    { max: 80,  color: '#fb923c', label: '60–80 km/h' },
    { max: 100, color: '#f472b6', label: '90–100 km/h' },
    { max: 999, color: '#ff4d6d', label: '110 km/h a více' },
  ];
  const IMPLICIT = { 'CZ:urban': 50, 'CZ:rural': 90, 'CZ:trunk': 110, 'CZ:motorway': 130,
                     'CZ:living_street': 20, 'CZ:pedestrian_zone': 20, 'walk': 20 };
  const CAM = {
    radar:   { label: 'Radar', title: 'Radar – měření rychlosti', say: 'radar', html: '<svg viewBox="0 0 24 24"><path d="M9 4 7.2 6H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-3.2L15 4H9zm3 4.5a4.5 4.5 0 1 1 0 9 4.5 4.5 0 0 1 0-9zm0 2a2.5 2.5 0 1 0 0 5 2.5 2.5 0 0 0 0-5z"/></svg>' },
    section: { label: 'Úsekové měření', title: 'Úsekové měření rychlosti', say: 'úsekové měření', html: 'Ú' },
    light:   { label: 'Kamera na semaforu', title: 'Kamera na semaforu (jízda na červenou)', say: 'kamera na semaforu', html: '🚦' },
  };
  const CATS = {
    fuel:    { icon: '⛽', label: 'Čerpací stanice', q: 'nwr["amenity"="fuel"]' },
    parking: { icon: '🅿️', label: 'Parkoviště', q: 'nwr["amenity"="parking"]["access"!~"^(private|no)$"]' },
    charge:  { icon: '🔌', label: 'Nabíječky', q: 'nwr["amenity"="charging_station"]' },
    food:    { icon: '🍽️', label: 'Jídlo a káva', q: 'nwr["amenity"~"^(restaurant|fast_food|cafe)$"]' },
    shop:    { icon: '🛒', label: 'Obchody', q: 'nwr["shop"~"^(supermarket|convenience)$"]' },
  };
  const KIND_ICON = { city: '🏙️', town: '🏙️', village: '🏡', hamlet: '🏡', house: '🏠', street: '🛣️', fuel: '⛽', restaurant: '🍽️',
    cafe: '☕', hotel: '🏨', supermarket: '🛒', parking: '🅿️', station: '🚉', aerodrome: '✈️', hospital: '🏥', school: '🏫' };
  const SAVED_ICON = { home: '🏠', work: '💼', fav: '⭐', recent: '🕘' };

  const store = {
    get(k) { try { return localStorage.getItem(k); } catch { return null; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* bez úložiště */ } },
    json(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch { return d; } },
    save(k, v) { this.set(k, JSON.stringify(v)); },
  };

  // ====================================================================
  // Stav
  // ====================================================================
  const cams = new Map();      // klíč -> {lat, lon, limit, kind}
  const roads = new Map();     // id -> {pts, limit, name}
  const loaded = { radar: null, speed: null };
  let ctrl = null, timer = null;

  let place = null, pin = null;                  // vybrané místo
  let routes = [], sel = 0, route = null;        // nalezené trasy a ta vybraná
  let mode = MODES[store.get('mode')] ? store.get('mode') : 'car';
  let nav = false, pickStart = false, lastReroute = 0, spoken = new Set(), wake = null;
  let cat = null;                                // aktivní kategorie {key, items}
  let favs = store.json('fav', []), recents = store.json('recent', []);

  let watchId = null, follow = false, me = null, meMarker = null, accCircle = null, prev = null, lastHeading = null;
  let autoCenter = true, overArmed = true;
  let askTimer = null, locDenied = false;
  const warned = new Set();

  // ====================================================================
  // Pomocné funkce
  // ====================================================================
  const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const on = id => $(id).getAttribute('aria-pressed') === 'true';
  const isMobile = () => matchMedia('(max-width: 640px)').matches;
  const colorFor = limit => BUCKETS.find(b => limit <= b.max).color;
  const RAD = Math.PI / 180;

  function setStatus(text, err) {
    $('status').textContent = text || '';
    $('status').hidden = !text;
    $('status').classList.toggle('err', !!err);
  }
  function parseLimit(v) {
    if (!v) return null;
    if (IMPLICIT[v] != null) return IMPLICIT[v];
    const m = /^(\d+(?:\.\d+)?)\s*(mph)?$/.exec(v.trim());
    return m ? Math.round(m[2] ? m[1] * 1.609 : +m[1]) : null;
  }
  function dist(a, b, c, d) {                       // metry mezi [a,b] a [c,d]
    const x = (d - b) * RAD * Math.cos((a + c) * RAD / 2), y = (c - a) * RAD;
    return Math.sqrt(x * x + y * y) * 6371000;
  }
  function distToSeg(p, a, b) {                     // metry od bodu k úsečce
    const k = Math.cos(p[0] * RAD);
    const px = p[1] * k, py = p[0], ax = a[1] * k, ay = a[0], bx = b[1] * k, by = b[0];
    const dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
    const t = l2 ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l2)) : 0;
    return Math.hypot(px - ax - t * dx, py - ay - t * dy) * 111320;
  }
  function bearing(a, b, c, d) {                    // směr ve stupních z [a,b] do [c,d]
    const y = Math.sin((d - b) * RAD) * Math.cos(c * RAD);
    const x = Math.cos(a * RAD) * Math.sin(c * RAD) - Math.sin(a * RAD) * Math.cos(c * RAD) * Math.cos((d - b) * RAD);
    return (Math.atan2(y, x) / RAD + 360) % 360;
  }
  const angDiff = (x, y) => Math.abs(((x - y + 540) % 360) - 180);
  const fmtDist = m => m < 1000 ? `${Math.max(10, Math.round(m / 10) * 10)} m`
    : `${(m / 1000).toFixed(m < 100000 ? 1 : 0).replace('.', ',')} km`;
  function fmtDur(s) {
    const min = Math.max(1, Math.round(s / 60));
    return min < 60 ? `${min} min` : `${Math.floor(min / 60)} h ${min % 60} min`;
  }
  const fmtClock = d => d.toLocaleTimeString('cs-CZ', { hour: '2-digit', minute: '2-digit' });
  const plural = (n, one, few, many) => n === 1 ? one : n > 1 && n < 5 ? few : many;
  const lcFirst = s => s.charAt(0).toLowerCase() + s.slice(1);

  async function overpass(query, signal) {
    let lastErr;
    for (const url of OVERPASS) {
      try {
        const r = await fetch(url, { method: 'POST', body: 'data=' + encodeURIComponent(query), signal,
          headers: { 'Content-Type': 'application/x-www-form-urlencoded' } });
        if (!r.ok) throw new Error('HTTP ' + r.status);
        return await r.json();
      } catch (e) { if (e.name === 'AbortError') throw e; lastErr = e; }
    }
    throw lastErr;
  }
  const bboxStr = b => [b.getSouth(), b.getWest(), b.getNorth(), b.getEast()].map(n => n.toFixed(5)).join(',');

  // ---------- zvuk a hlas ----------
  function beep(freq = 880, times = 2) {
    if (!on('tSound')) return;
    try {
      const ac = beep.ac || (beep.ac = new (window.AudioContext || window.webkitAudioContext)());
      if (ac.state === 'suspended') ac.resume();
      for (let i = 0; i < times; i++) {
        const o = ac.createOscillator(), g = ac.createGain(), t = ac.currentTime + i * .22;
        o.frequency.value = freq; g.gain.value = .15;
        o.connect(g).connect(ac.destination);
        o.start(t); o.stop(t + .14);
      }
    } catch { /* zvuk není k dispozici */ }
  }
  function say(text) {
    if (!on('tVoice') || !('speechSynthesis' in window)) return;
    try {
      const u = new SpeechSynthesisUtterance(text);
      u.lang = 'cs-CZ';
      const v = speechSynthesis.getVoices().find(x => /^cs/i.test(x.lang));
      if (v) u.voice = v;
      if (speechSynthesis.pending) speechSynthesis.cancel();
      speechSynthesis.speak(u);
    } catch { /* hlas není k dispozici */ }
  }
  function sayDist(m) {
    if (m >= 950) { const km = Math.round(m / 100) / 10; return `${String(km).replace('.', ',')} ${km === 1 ? 'kilometr' : km < 5 ? 'kilometry' : 'kilometrů'}`; }
    return `${Math.max(50, Math.round(m / 50) * 50)} metrů`;
  }
  function sayDur(s) {
    const min = Math.max(1, Math.round(s / 60)), h = Math.floor(min / 60), m = min % 60;
    const part = (n, one, few, many) => n ? `${n} ${plural(n, one, few, many)}` : '';
    return [part(h, 'hodina', 'hodiny', 'hodin'), part(m, 'minuta', 'minuty', 'minut')].filter(Boolean).join(' a ');
  }
  // iPhone dovolí zvuk a hlas až po prvním dotyku – tím je odemkneme
  addEventListener('pointerdown', () => {
    try {
      if ('speechSynthesis' in window) speechSynthesis.speak(new SpeechSynthesisUtterance(''));
      const AC = window.AudioContext || window.webkitAudioContext;
      if (AC && !beep.ac) beep.ac = new AC();
      if (beep.ac && beep.ac.state === 'suspended') beep.ac.resume();
    } catch { /* nevadí */ }
  }, { once: true });

  // ====================================================================
  // Mapa a podklady
  // ====================================================================
  $('legSpeed').innerHTML = BUCKETS.map(b =>
    `<li><span class="sw" style="background:${b.color}"></span>${b.label}</li>`).join('') +
    Object.entries(CAM).map(([k, c]) => `<li><span class="radar mini ${k}">${k === 'radar' ? '' : c.html}</span>${c.label}</li>`).join('');

  const map = L.map('map', { preferCanvas: true, zoomControl: false }).setView([49.82, 15.47], 8);
  map.attributionControl.setPrefix(false);
  const OSM = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>';
  const bases = {
    purple: L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, className: 'tint', attribution: OSM, crossOrigin: true }),
    light: L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19, attribution: OSM, crossOrigin: true }),
    sat: L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}',
      { maxZoom: 19, attribution: 'Snímky &copy; Esri, Maxar, Earthstar Geographics · data ' + OSM }),
  };
  let baseType = null;
  function setBase(t) {
    if (!bases[t]) t = 'purple';
    if (baseType) bases[baseType].remove();
    bases[t].addTo(map).bringToBack();
    baseType = t; store.set('mapType', t);
    document.querySelectorAll('.types button').forEach(b => b.setAttribute('aria-pressed', b.dataset.type === t));
  }
  setBase(store.get('mapType') || 'purple');
  document.querySelectorAll('.types button').forEach(b => b.addEventListener('click', () => setBase(b.dataset.type)));

  const speedLayer = L.layerGroup().addTo(map);
  const routeLayer = L.layerGroup().addTo(map);
  const poiLayer = L.layerGroup().addTo(map);
  const radarLayer = L.layerGroup().addTo(map);

  const camIcon = {};
  for (const [k, c] of Object.entries(CAM)) camIcon[k] = L.divIcon({
    className: '', iconSize: [30, 30], iconAnchor: [15, 15], popupAnchor: [0, -14], html: `<div class="radar ${k}">${c.html}</div>` });
  const pinIcon = L.divIcon({
    className: '', iconSize: [34, 44], iconAnchor: [17, 42],
    html: '<svg class="pin" viewBox="0 0 34 44"><defs><linearGradient id="pg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#c084fc"/><stop offset="1" stop-color="#c026d3"/></linearGradient></defs><path d="M17 1C8.2 1 1 8 1 16.8 1 28.5 17 43 17 43s16-14.5 16-26.2C33 8 25.8 1 17 1z" fill="url(#pg)" stroke="#fff" stroke-width="2"/><circle cx="17" cy="17" r="6" fill="#fff"/></svg>',
  });

  // ====================================================================
  // Radary, úsekové měření, kamery na semaforech a rychlostní limity
  // ====================================================================
  const camQuery = bb => `(node["highway"="speed_camera"](${bb});rel["type"="enforcement"](${bb});)->.a;.a out body;node(r.a);out skel;`;

  function addCam(key, lat, lon, kind, tags, end) {
    if (cams.has(key) || lat == null) return;
    const t = tags || {}, limit = parseLimit(t.maxspeed);
    cams.set(key, { lat, lon, limit, kind, end: !!end });
    let html = `<b>${kind === 'light' ? '🚦' : '📷'} ${end ? 'Konec úsekového měření' : CAM[kind].title}</b>`;
    if (kind !== 'light') html += limit ? `<br>Měřený limit: <b>${limit} km/h</b>` : '<br>Limit není v datech uveden.';
    if (t.name) html += '<br>' + esc(t.name);
    if (t.description) html += '<br>' + esc(t.description);
    L.marker([lat, lon], { icon: camIcon[kind], title: CAM[kind].label }).bindPopup(html).addTo(radarLayer);
  }
  function addRoad(el) {
    if (roads.has(el.id) || !el.tags) return;
    const limit = parseLimit(el.tags.maxspeed);
    if (!limit || !el.geometry) return;
    const pts = el.geometry.map(p => [p.lat, p.lon]);
    const name = el.tags.name || el.tags.ref || '';
    roads.set(el.id, { pts, limit, name });
    L.polyline(pts, { color: colorFor(limit), weight: 3, opacity: .8 })
      .bindTooltip(`${limit} km/h${name ? ' · ' + esc(name) : ''}`, { sticky: true })
      .addTo(speedLayer);
  }
  function ingest(data) {
    const nodes = new Map();
    for (const el of data.elements) if (el.type === 'node' && (el.tags || !nodes.has(el.id))) nodes.set(el.id, el);
    // nejdřív relace – nesou limit a druh měření
    for (const el of data.elements) {
      if (el.type !== 'relation' || !el.tags) continue;
      const kind = { maxspeed: 'radar', average_speed: 'section', traffic_signals: 'light' }[el.tags.enforcement];
      if (!kind) continue;
      for (const m of el.members || []) {
        if (m.type !== 'node') continue;
        if (kind === 'section' ? !/^(from|to|device)$/.test(m.role) : m.role !== 'device') continue;
        const n = nodes.get(m.ref);
        if (n) addCam('n' + m.ref, n.lat, n.lon, kind, { ...(n.tags || {}), maxspeed: el.tags.maxspeed || (n.tags || {}).maxspeed }, kind === 'section' && m.role === 'to');
      }
    }
    for (const el of data.elements) {
      if (el.type === 'node' && el.tags && el.tags.highway === 'speed_camera') addCam('n' + el.id, el.lat, el.lon, 'radar', el.tags);
      else if (el.type === 'way') addRoad(el);
    }
  }
  function countInView() {
    const b = map.getBounds(); let n = 0;
    for (const r of cams.values()) if (!r.end && b.contains([r.lat, r.lon])) n++;
    return n;
  }
  function viewStatus() {
    const z = map.getZoom();
    if (!on('tRadar') && !on('tSpeed')) return setStatus('');
    if (z < RADAR_MIN_ZOOM) return setStatus('Přibliž mapu, aby se zobrazily radary a limity.');
    let s = on('tRadar') ? `Radarů a měření v okolí: ${countInView()}` : '';
    if (on('tSpeed') && z < SPEED_MIN_ZOOM) s += (s ? ' · ' : '') + 'pro limity přibliž ještě víc';
    setStatus(s);
  }
  async function refresh() {
    const z = map.getZoom(), view = map.getBounds();
    const needRadar = on('tRadar') && z >= RADAR_MIN_ZOOM && !(loaded.radar && loaded.radar.contains(view));
    const needSpeed = on('tSpeed') && z >= SPEED_MIN_ZOOM && !(loaded.speed && loaded.speed.contains(view));
    if (!needRadar && !needSpeed) return viewStatus();

    const area = view.pad(needSpeed ? 0.3 : 0.6), bb = bboxStr(area);
    let q = '[out:json][timeout:25];';
    if (needRadar) q += camQuery(bb);
    if (needSpeed) q += `way["highway"]["maxspeed"](${bb});out geom;`;

    if (ctrl) ctrl.abort();
    ctrl = new AbortController();
    setStatus('Načítám radary a limity…');
    try {
      ingest(await overpass(q, ctrl.signal));
      if (needRadar) loaded.radar = area;
      if (needSpeed) loaded.speed = area;
      viewStatus();
      updateDrive();
    } catch (e) {
      if (e.name !== 'AbortError') setStatus('Radary a limity se nepodařilo načíst. Zkus to za chvíli znovu.', true);
    }
  }
  map.on('moveend', () => { clearTimeout(timer); timer = setTimeout(refresh, 450); });

  // ---------- přepínače v nabídce vrstev ----------
  function toggle(id, fn) {
    if (store.get(id) === '0') $(id).setAttribute('aria-pressed', 'false');
    $(id).addEventListener('click', () => {
      const v = !on(id);
      $(id).setAttribute('aria-pressed', v);
      store.set(id, v ? '1' : '0');
      fn && fn(v);
    });
    fn && !on(id) && fn(false, true);
  }
  toggle('tRadar', (v, init) => { v ? radarLayer.addTo(map) : radarLayer.remove(); init || refresh(); });
  toggle('tSpeed', (v, init) => { v ? speedLayer.addTo(map) : speedLayer.remove(); init || refresh(); });
  toggle('tSound');
  toggle('tVoice', v => { if (v) say('Hlasové pokyny zapnuty.'); else if ('speechSynthesis' in window) speechSynthesis.cancel(); });

  // ---------- plovoucí tlačítka ----------
  $('zin').addEventListener('click', () => map.zoomIn());
  $('zout').addEventListener('click', () => map.zoomOut());
  $('layersBtn').addEventListener('click', e => { e.stopPropagation(); $('layers').hidden = !$('layers').hidden; });
  $('layers').addEventListener('click', e => e.stopPropagation());
  document.addEventListener('click', () => { $('layers').hidden = true; hideSugg(); });

  // ====================================================================
  // Karta (místo / seznam / trasa / navigace)
  // ====================================================================
  const sheet = $('sheet'), body = $('sheetBody');
  function syncSheet() {
    document.documentElement.style.setProperty('--sheet-h', (isMobile() && !sheet.hidden ? sheet.offsetHeight : 0) + 'px');
  }
  new ResizeObserver(syncSheet).observe(sheet);
  addEventListener('resize', syncSheet);
  function openSheet(html) { body.innerHTML = html; sheet.hidden = false; sheet.classList.remove('mini'); body.scrollTop = 0; syncSheet(); }
  function closeSheet() { sheet.hidden = true; syncSheet(); }
  $('grab').addEventListener('click', () => { sheet.classList.toggle('mini'); body.scrollTop = 0; });
  body.addEventListener('click', e => {
    const a = e.target.closest('[data-act]');
    if (a) actions[a.dataset.act](a.dataset.arg);
  });

  const samePlace = (a, b) => Math.abs(a.lat - b.lat) < 1e-5 && Math.abs(a.lon - b.lon) < 1e-5;
  const findFav = p => favs.find(f => samePlace(f, p));

  function tagInfo(t) {
    if (!t) return '';
    const rows = [];
    const addr = [[t['addr:street'] || t['addr:place'], t['addr:housenumber']].filter(Boolean).join(' '), t['addr:city']].filter(Boolean).join(', ');
    if (addr) rows.push(['📍', esc(addr)]);
    if (t.opening_hours) rows.push(['🕒', esc(t.opening_hours === '24/7' ? 'Otevřeno nonstop' : t.opening_hours)]);
    if (t.fee) rows.push(['💳', t.fee === 'no' ? 'Zdarma' : t.fee === 'yes' ? 'Placené' : esc(t.fee)]);
    if (t.capacity) rows.push(['🚘', `Kapacita: ${esc(t.capacity)}`]);
    if (t.cuisine) rows.push(['🍴', esc(t.cuisine.replace(/_/g, ' ').replace(/;/g, ', '))]);
    const phone = t.phone || t['contact:phone'];
    if (phone) rows.push(['📞', `<a href="tel:${esc(phone.replace(/[^\d+]/g, ''))}">${esc(phone)}</a>`]);
    const web = t.website || t['contact:website'];
    if (web && /^https?:\/\//i.test(web)) rows.push(['🌐', `<a href="${esc(web)}" target="_blank" rel="noopener">${esc(web.replace(/^https?:\/\/(www\.)?/i, '').replace(/\/$/, ''))}</a>`]);
    return rows.length ? `<ul class="info">${rows.map(r => `<li><span>${r[0]}</span><span>${r[1]}</span></li>`).join('')}</ul>` : '';
  }

  function showPlace(p, fly) {
    clearRoutes();
    place = p;
    if (pin) pin.remove();
    pin = L.marker([p.lat, p.lon], { icon: pinIcon, zIndexOffset: 500, interactive: false }).addTo(map);
    const fav = findFav(p);
    const far = me ? `<div class="badges"><span class="badge">📍 ${fmtDist(dist(me.lat, me.lon, p.lat, p.lon))} vzdušnou čarou</span></div>` : '';
    openSheet(`<button class="x" data-act="close" aria-label="Zavřít">×</button>
      <h2 class="ttl">${esc(p.name)}</h2>${p.sub ? `<p class="sub">${esc(p.sub)}</p>` : ''}${far}${tagInfo(p.tags)}
      <div class="row" id="placeRow">
        <button class="btn primary" data-act="route">➤ Trasa</button>
        <button class="btn" data-act="save">${fav ? SAVED_ICON[fav.kind] + ' Uloženo' : '☆ Uložit'}</button>
        <button class="btn" data-act="share" aria-label="Sdílet">↗ Sdílet</button>
      </div>`);
    if (fly) {      // na telefonu necháme místo vykouknout nad spodní kartou
      const pad = isMobile() ? { paddingTopLeft: [20, 130], paddingBottomRight: [20, sheet.offsetHeight + 20] } : { paddingTopLeft: [430, 30], paddingBottomRight: [70, 30] };
      map.fitBounds(p.bounds || L.latLng(p.lat, p.lon).toBounds(400), { ...pad, maxZoom: 17 });
    }
  }

  const actions = {
    close() {
      clearRoutes(); place = null;
      if (pin) { pin.remove(); pin = null; }
      cat ? showCatList() : closeSheet();
    },
    route() { startRouting(); },
    back() { clearRoutes(); if (place) showPlace(place, false); },
    go() { startNav(); },
    stop() { stopNav(); showRoute(); fitRoute(); },
    alt(i) { selectRoute(+i); },
    mode(m) { mode = m; store.set('mode', m); if (route) buildRoute(route.from); },
    pickStart() { pickStart = true; setStatus('Klepni do mapy na místo, odkud chceš vyjet.'); if (isMobile()) sheet.classList.add('mini'); },
    save() {
      const f = findFav(place);
      if (f) { favs = favs.filter(x => x !== f); store.save('fav', favs); return showPlace(place, false); }
      $('placeRow').innerHTML = `<button class="btn small" data-act="saveAs" data-arg="home">🏠 Domů</button>
        <button class="btn small" data-act="saveAs" data-arg="work">💼 Práce</button>
        <button class="btn small" data-act="saveAs" data-arg="fav">⭐ Oblíbené</button>`;
    },
    saveAs(kind) {
      if (kind !== 'fav') favs = favs.filter(f => f.kind !== kind);
      favs.push({ kind, name: place.name, sub: place.sub || '', lat: place.lat, lon: place.lon });
      store.save('fav', favs);
      showPlace(place, false);
    },
    async share() {
      const url = `${location.origin}${location.pathname}#p=${place.lat.toFixed(5)},${place.lon.toFixed(5)},${encodeURIComponent(place.name)}`;
      try {
        if (navigator.share) await navigator.share({ title: place.name, url });
        else { await navigator.clipboard.writeText(url); setStatus('Odkaz na místo je zkopírovaný.'); }
      } catch { /* sdílení zrušeno */ }
    },
    poi(i) { const p = cat && cat.items[+i]; if (p) showPlace(p, true); },
    catClose() { clearCat(); },
    locRetry() { closeSheet(); askLocation(); },
  };

  // ====================================================================
  // Hledání: našeptávač, uložená a nedávná místa
  // ====================================================================
  const q = $('q'), sugg = $('sugg');
  let hits = [], hi = -1, sTimer = null, sCtrl = null;

  function hideSugg() { sugg.hidden = true; hi = -1; }
  function renderSugg(list, head) {
    hits = list; hi = -1;
    sugg.innerHTML = (head && list.length ? `<li class="head">${head}</li>` : '') + list.map((h, i) =>
      `<li data-i="${i}" role="option"><span class="ic">${h.icon || '📍'}</span>
        <div><div class="t">${esc(h.name)}</div><div class="s">${esc(h.sub || '')}</div></div></li>`).join('');
    sugg.hidden = !list.length;
  }
  function savedList() {
    const order = { home: 0, work: 1, fav: 2 };
    return [...favs].sort((a, b) => order[a.kind] - order[b.kind]).map(f => ({ ...f, icon: SAVED_ICON[f.kind] }))
      .concat(recents.filter(r => !findFav(r)).map(r => ({ ...r, icon: SAVED_ICON.recent })));
  }
  function fromPhoton(f) {
    const p = f.properties, [lon, lat] = f.geometry.coordinates;
    const street = [p.street, p.housenumber].filter(Boolean).join(' ');
    const name = p.name || street || p.city || 'Místo';
    const sub = [p.name && street, p.city !== name && p.city, p.county !== p.city && p.county, p.country]
      .filter(Boolean).filter((v, i, a) => a.indexOf(v) === i).join(', ');
    const e = p.extent;
    return { lat, lon, name, sub, icon: KIND_ICON[p.osm_value] || KIND_ICON[p.type] || '📍',
      bounds: e ? [[e[3], e[0]], [e[1], e[2]]] : null };
  }
  async function suggest() {
    const text = q.value.trim();
    if (text.length < 2) return renderSugg(savedList(), 'Uložená a nedávná místa');
    if (sCtrl) sCtrl.abort();
    sCtrl = new AbortController();
    const c = map.getCenter();
    try {
      const r = await fetch(`https://photon.komoot.io/api/?limit=6&lat=${c.lat.toFixed(3)}&lon=${c.lng.toFixed(3)}&q=${encodeURIComponent(text)}`, { signal: sCtrl.signal });
      const found = (await r.json()).features.map(fromPhoton);
      const low = text.toLowerCase();
      const mine = savedList().filter(s => s.name.toLowerCase().includes(low)).slice(0, 2);
      if (q.value.trim() === text) renderSugg(mine.concat(found));
    } catch { /* našeptávač není nutný, hledání funguje i bez něj */ }
  }
  function pick(h, remember) {
    q.value = h.name; $('clear').hidden = false; hideSugg(); q.blur(); setFollow(false);
    if (remember) {
      recents = [{ name: h.name, sub: h.sub || '', lat: h.lat, lon: h.lon }].concat(recents.filter(r => !samePlace(r, h))).slice(0, 6);
      store.save('recent', recents);
    }
    showPlace({ lat: h.lat, lon: h.lon, name: h.name, sub: h.sub, bounds: h.bounds }, true);
  }

  q.addEventListener('input', () => {
    $('clear').hidden = !q.value;
    clearTimeout(sTimer); sTimer = setTimeout(suggest, 250);
  });
  q.addEventListener('focus', () => { if (q.value.trim().length < 2) renderSugg(savedList(), 'Uložená a nedávná místa'); else if (hits.length) sugg.hidden = false; });
  q.addEventListener('click', e => e.stopPropagation());
  q.addEventListener('keydown', e => {
    if (sugg.hidden || !hits.length) return;
    if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
      e.preventDefault();
      hi = (hi + (e.key === 'ArrowDown' ? 1 : -1) + hits.length) % hits.length;
      sugg.querySelectorAll('li[data-i]').forEach((li, i) => li.classList.toggle('sel', i === hi));
    } else if (e.key === 'Escape') hideSugg();
  });
  sugg.addEventListener('click', e => {
    e.stopPropagation();
    const li = e.target.closest('li[data-i]');
    if (li) pick(hits[+li.dataset.i], true);
  });
  $('clear').addEventListener('click', e => { e.stopPropagation(); q.value = ''; $('clear').hidden = true; q.focus(); renderSugg(savedList(), 'Uložená a nedávná místa'); });
  $('search').addEventListener('submit', async e => {
    e.preventDefault();
    clearTimeout(sTimer);
    const text = q.value.trim();
    if (!text) return;
    if (!sugg.hidden && hits.length && text.length > 1) return pick(hits[Math.max(hi, 0)], true);
    setStatus('Hledám…');
    try {
      const r = await fetch('https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=cs&q=' + encodeURIComponent(text));
      const [h] = await r.json();
      if (!h) return setStatus('Nic jsem nenašel. Zkus jiný název.', true);
      const [s, n, w, ea] = h.boundingbox.map(Number), parts = h.display_name.split(', ');
      setStatus('');
      pick({ lat: +h.lat, lon: +h.lon, name: parts[0], sub: parts.slice(1).join(', '), bounds: [[s, w], [n, ea]] }, true);
    } catch { setStatus('Hledání se nepovedlo. Zkontroluj připojení.', true); }
  });

  // klepnutí do mapy = špendlík s adresou (nebo výběr startu trasy)
  map.on('click', async e => {
    $('layers').hidden = true; hideSugg();
    const { lat, lng } = e.latlng;
    if (pickStart) { pickStart = false; setStatus(''); return buildRoute({ lat, lon: lng, mine: false }); }
    if (nav) return;
    showPlace({ lat, lon: lng, name: 'Označené místo', sub: `${lat.toFixed(5)}, ${lng.toFixed(5)}` }, false);
    const mine = place;
    try {
      const r = await fetch(`https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=18&accept-language=cs&lat=${lat}&lon=${lng}`);
      const d = await r.json();
      if (place !== mine || !d.display_name) return;
      const parts = d.display_name.split(', ');
      showPlace({ lat, lon: lng, name: d.name || parts[0], sub: parts.slice(d.name && parts[0] === d.name ? 1 : d.name ? 0 : 1).join(', ') }, false);
    } catch { /* zůstanou souřadnice */ }
  });

  // ====================================================================
  // Kategorie v okolí (pumpy, parkoviště, nabíječky, jídlo, obchody)
  // ====================================================================
  const catChips = [...document.querySelectorAll('.chip.cat')];
  catChips.forEach(ch => ch.addEventListener('click', () => cat && cat.key === ch.dataset.cat ? clearCat() : showCat(ch.dataset.cat)));

  function clearCat() {
    cat = null;
    poiLayer.clearLayers();
    catChips.forEach(c => c.removeAttribute('aria-pressed'));
    if (!place && !route) closeSheet();
  }
  async function showCat(key) {
    const c = CATS[key];
    if (map.getZoom() < POI_MIN_ZOOM) return setStatus(`Pro hledání „${c.label}“ přibliž mapu.`);
    stopNav(); clearRoutes(); place = null;
    if (pin) { pin.remove(); pin = null; }
    poiLayer.clearLayers();
    catChips.forEach(ch => ch.dataset.cat === key ? ch.setAttribute('aria-pressed', 'true') : ch.removeAttribute('aria-pressed'));
    cat = { key, items: [] };
    const mine = cat;
    openSheet(`<p class="sub" style="margin:0">Hledám: ${c.label}…</p>`);
    try {
      const data = await overpass(`[out:json][timeout:25];${c.q}(${bboxStr(map.getBounds())});out center 80;`);
      if (cat !== mine) return;
      const o = me || { lat: map.getCenter().lat, lon: map.getCenter().lng };
      const icon = L.divIcon({ className: '', iconSize: [32, 32], iconAnchor: [16, 16], html: `<div class="poi">${c.icon}</div>` });
      cat.items = data.elements.map(el => {
        const t = el.tags || {}, lat = el.lat != null ? el.lat : el.center && el.center.lat, lon = el.lon != null ? el.lon : el.center && el.center.lon;
        if (lat == null) return null;
        const street = [t['addr:street'], t['addr:housenumber']].filter(Boolean).join(' ');
        return { lat, lon, tags: t, name: t.name || t.brand || t.operator || c.label,
          sub: [t.name && t.brand !== t.name && t.brand, street, t['addr:city']].filter(Boolean).join(', ') || c.label,
          d: dist(o.lat, o.lon, lat, lon) };
      }).filter(Boolean).sort((a, b) => a.d - b.d);
      cat.items.forEach((p, i) => L.marker([p.lat, p.lon], { icon, title: p.name }).on('click', () => actions.poi(i)).addTo(poiLayer));
      showCatList();
    } catch {
      if (cat === mine) openSheet(`<button class="x" data-act="catClose" aria-label="Zavřít">×</button><h2 class="ttl">Nepodařilo se načíst</h2><p class="sub">Zkus to za chvíli znovu.</p>`);
    }
  }
  function showCatList() {
    const c = CATS[cat.key], n = cat.items.length;
    openSheet(`<button class="x" data-act="catClose" aria-label="Zavřít">×</button>
      <h2 class="ttl">${c.icon} ${c.label}</h2>
      <p class="sub">${n ? `${n}${n === 80 ? '+' : ''} v zobrazené oblasti, od nejbližšího` : 'V zobrazené oblasti nic není. Posuň mapu a klepni na kategorii znovu.'}</p>
      ${n ? `<ul class="list">${cat.items.map((p, i) => `<li data-act="poi" data-arg="${i}"><span class="ic">${c.icon}</span>
        <div><div class="t">${esc(p.name)}</div><div class="s">${esc(p.tags.opening_hours === '24/7' ? 'Nonstop · ' + p.sub : p.sub)}</div></div>
        <span class="d">${fmtDist(p.d)}</span></li>`).join('')}</ul>` : ''}`);
  }

  // ====================================================================
  // Trasy
  // ====================================================================
  const ARROW = { left: '←', right: '→', 'slight left': '↖', 'slight right': '↗', 'sharp left': '↙', 'sharp right': '↘', straight: '↑', uturn: '↩' };
  const SIDE = { left: 'vlevo', right: 'vpravo', 'slight left': 'mírně vlevo', 'slight right': 'mírně vpravo',
    'sharp left': 'ostře vlevo', 'sharp right': 'ostře vpravo', straight: 'rovně', uturn: 'a otoč se' };
  function instruction(s) {
    const m = s.maneuver, mod = m.modifier, side = SIDE[mod] || '';
    const onto = s.name ? ` na ${s.name}` : s.ref ? ` na ${s.ref}` : '';
    let text, icon = ARROW[mod] || '↑';
    switch (m.type) {
      case 'depart': text = 'Vyraz' + (s.name ? ` po ${s.name}` : ''); icon = '↑'; break;
      case 'arrive': text = 'Cíl' + (mod === 'left' ? ' je vlevo' : mod === 'right' ? ' je vpravo' : ''); icon = '⚑'; break;
      case 'roundabout': case 'rotary':
        text = `Na kruhovém objezdu vyjeď ${m.exit || 1}. výjezdem${onto}`; icon = '⟳'; break;
      case 'exit roundabout': case 'exit rotary': text = 'Sjeď z kruhového objezdu' + onto; break;
      case 'merge': text = 'Zařaď se' + onto; break;
      case 'on ramp': text = 'Najeď na nájezd' + onto; break;
      case 'off ramp': text = `Sjeď sjezdem ${side}`.trim() + onto; break;
      case 'fork': text = `Na rozcestí se drž ${side}`.trim() + onto; break;
      case 'end of road': text = `Na konci silnice odboč ${side}`.trim() + onto; break;
      case 'new name': case 'continue': case 'notification':
        text = (mod === 'uturn' ? 'Otoč se' : mod && mod !== 'straight' ? `Drž se ${side}` : 'Pokračuj') + onto; break;
      default:
        text = (mod === 'uturn' ? 'Otoč se' : mod === 'straight' ? 'Pokračuj rovně' : `Odboč ${side}`) + onto;
    }
    return { text, icon };
  }

  function startRouting() {
    if (!place) return;
    if (me) return buildRoute({ lat: me.lat, lon: me.lon, mine: true });
    const ask = () => { pickStart = true; setStatus('Polohu neznám – klepni do mapy na místo, odkud vyjíždíš.'); if (isMobile()) sheet.classList.add('mini'); };
    if (!navigator.geolocation) return ask();
    setStatus('Zjišťuji tvou polohu…');
    navigator.geolocation.getCurrentPosition(pos => { onPos(pos); setStatus(''); buildRoute({ lat: me.lat, lon: me.lon, mine: true }); }, ask,
      { enableHighAccuracy: true, timeout: 10000 });
  }

  async function fetchRoutes(from, to) {
    let lastErr;
    for (const base of ROUTERS[mode]) {
      try {
        const r = await fetch(`${base}${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson&steps=true&alternatives=true`);
        const d = await r.json();
        if (d.code !== 'Ok' || !d.routes || !d.routes.length) throw new Error(d.code || 'bez trasy');
        return d.routes;
      } catch (e) { lastErr = e; }
    }
    throw lastErr;
  }
  function parseRoute(R, from) {
    const pts = [], steps = [];
    for (const leg of R.legs) for (const s of leg.steps) {
      const idx = Math.max(0, pts.length - 1);
      s.geometry.coordinates.forEach(([lon, lat], i) => { if (i || !pts.length) pts.push([lat, lon]); });
      steps.push({ idx, dist: s.distance, ...instruction(s) });
    }
    const cum = [0];
    for (let i = 1; i < pts.length; i++) cum.push(cum[i - 1] + dist(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1]));
    return { pts, cum, steps, dist: R.distance, dur: R.duration, from, cams: null, near: 0 };
  }

  async function buildRoute(from, quiet) {
    const to = place, m = mode;
    if (!to) return;
    if (!quiet) openSheet('<p class="sub" style="margin:0">Počítám trasu…</p>');
    try {
      const raw = await fetchRoutes(from, to);
      if (place !== to || mode !== m) return;
      routes = raw.slice(0, 3).map(R => parseRoute(R, from));
      sel = 0; route = routes[0]; spoken = new Set();
      setStatus('');
      drawRoutes();
      if (nav) updateNav(); else { showRoute(); fitRoute(); }
      if (mode === 'car') routeCams(routes);
    } catch {
      if (quiet) return;
      openSheet(`<button class="x" data-act="back" aria-label="Zpět">×</button><h2 class="ttl">Trasu se nepodařilo najít</h2>
        <p class="sub">Zkus jiné místo, jiný způsob dopravy nebo to zopakuj za chvíli.</p>`);
    }
  }
  function drawRoutes() {
    routeLayer.clearLayers();
    if (!route) return;
    if (!nav) routes.forEach((r, i) => {
      if (i === sel) return;
      L.polyline(r.pts, { color: '#8b7fb0', weight: 7, opacity: .75 })
        .on('click', e => { L.DomEvent.stopPropagation(e); selectRoute(i); }).addTo(routeLayer);
    });
    L.polyline(route.pts, { color: '#fff', weight: 10, opacity: .95, interactive: false }).addTo(routeLayer);
    L.polyline(route.pts, { color: '#a855f7', weight: 6, opacity: 1, interactive: false }).addTo(routeLayer);
    if (!route.from.mine) L.circleMarker([route.from.lat, route.from.lon], { radius: 7, color: '#fff', weight: 3, fillColor: '#a855f7', fillOpacity: 1, interactive: false }).addTo(routeLayer);
  }
  function selectRoute(i) {
    if (!routes[i] || nav) return;
    sel = i; route = routes[i];
    drawRoutes(); showRoute();
  }
  function fitRoute() {
    if (!route) return;
    const b = L.latLngBounds(route.pts);
    routes.forEach(r => b.extend(L.latLngBounds(r.pts)));
    map.fitBounds(b, isMobile()
      ? { paddingTopLeft: [30, 140], paddingBottomRight: [30, sheet.offsetHeight + 30] }
      : { paddingTopLeft: [440, 40], paddingBottomRight: [80, 40] });
  }
  function clearRoutes() {
    stopNav();
    routes = []; route = null; pickStart = false;
    routeLayer.clearLayers();
  }

  // radary a měření ležící přímo na trasách
  async function routeCams(rs) {
    const b = L.latLngBounds(rs[0].pts);
    rs.forEach(r => b.extend(L.latLngBounds(r.pts)));
    const area = b.pad(0.02);
    try { ingest(await overpass(`[out:json][timeout:25];${camQuery(bboxStr(area))}`)); }
    catch { /* spočítáme aspoň z toho, co už je načtené */ }
    if (routes !== rs) return;
    for (const rt of rs) {
      const list = [];
      for (const c of cams.values()) {
        if (c.end || !area.contains([c.lat, c.lon])) continue;
        const p = [c.lat, c.lon];
        for (let i = 1; i < rt.pts.length; i++) {
          const a = rt.pts[i - 1];
          if (Math.abs(a[0] - p[0]) > .01 || Math.abs(a[1] - p[1]) > .02) continue;
          if (distToSeg(p, a, rt.pts[i]) < CAM_ON_ROUTE_M) { list.push({ d: rt.cum[i - 1] + dist(a[0], a[1], c.lat, c.lon), ...c }); break; }
        }
      }
      rt.cams = list.sort((x, y) => x.d - y.d);
    }
    nav ? updateNav() : showRoute();
  }

  function showRoute() {
    if (!route) return;
    const car = mode === 'car';
    const camTxt = r => !car ? '' : r.cams == null ? '<span>📷 hledám…</span>'
      : r.cams.length ? `<span class="cam">📷 ${r.cams.length} ${plural(r.cams.length, 'radar', 'radary', 'radarů')}</span>` : '<span>📷 bez radarů</span>';
    openSheet(`<button class="x" data-act="back" aria-label="Zavřít trasu">×</button>
      <div class="modes">${Object.entries(MODES).map(([k, l]) => `<button data-act="mode" data-arg="${k}" aria-pressed="${k === mode}">${l}</button>`).join('')}</div>
      <p class="sub">${route.from.mine ? 'Moje poloha' : 'Vybraný bod'} → ${esc(place.name)} · <button class="link" data-act="pickStart">změnit start</button></p>
      <div class="alts">${routes.map((r, i) => `<button class="alt${i === sel ? ' sel' : ''}" data-act="alt" data-arg="${i}">
        <b>${fmtDur(r.dur)}</b><span>${fmtDist(r.dist)} · příjezd ${fmtClock(new Date(Date.now() + r.dur * 1000))}</span>${camTxt(r)}</button>`).join('')}</div>
      <div class="row"><button class="btn primary" data-act="go">▲ Spustit navigaci</button></div>
      <ol id="steps" class="list">${route.steps.map(s => `<li><span class="ar">${s.icon}</span><span>${esc(s.text)}</span>${s.dist ? `<span class="d">${fmtDist(s.dist)}</span>` : ''}</li>`).join('')}</ol>`);
  }

  // ====================================================================
  // Navigace
  // ====================================================================
  async function keepAwake(v) {
    try {
      if (v && 'wakeLock' in navigator && !wake) { wake = await navigator.wakeLock.request('screen'); wake.addEventListener('release', () => { wake = null; }); }
      else if (!v && wake) { await wake.release(); wake = null; }
    } catch { wake = null; }
  }
  document.addEventListener('visibilitychange', () => { if (nav && document.visibilityState === 'visible') keepAwake(true); });

  function startNav() {
    if (!route) return;
    nav = true; spoken = new Set();
    keepAwake(true);
    startWatch(); setFollow(true);
    drawRoutes();
    openSheet('<div id="navBox"></div>');
    say(`Navigace spuštěna. Do cíle ${sayDur(route.dur)}.`);
    updateNav();
  }
  function stopNav() {
    if (!nav) return;
    nav = false;
    keepAwake(false);
    setFollow(false);
    if ('speechSynthesis' in window) speechSynthesis.cancel();
    drawRoutes();
  }
  function updateNav() {
    if (!nav || !route) return;
    let idx = 0, off = Infinity;
    if (me) {
      const p = [me.lat, me.lon];
      for (let i = 1; i < route.pts.length; i++) {
        const a = route.pts[i - 1];
        if (Math.abs(a[0] - p[0]) > .02 || Math.abs(a[1] - p[1]) > .04) continue;
        const d = distToSeg(p, a, route.pts[i]);
        if (d < off) { off = d; idx = i; }
      }
      if (off > OFF_ROUTE_M && Date.now() - lastReroute > 10000) {     // sjel jsi z trasy -> přepočet
        lastReroute = Date.now();
        setStatus('Přepočítávám trasu…');
        say('Přepočítávám trasu.');
        return buildRoute({ lat: me.lat, lon: me.lon, mine: true }, true);
      }
      if (off === Infinity) idx = route.near;
      route.near = idx;
    }
    const total = route.cum[route.cum.length - 1];
    const done = Math.min(total, route.cum[Math.max(0, idx - 1)] +
      (me && idx > 0 && off < OFF_ROUTE_M ? dist(route.pts[idx - 1][0], route.pts[idx - 1][1], me.lat, me.lon) : 0));
    const left = total - done;
    const box = $('navBox');
    if (!box) return;
    if (me && left < 30) {
      stopNav();
      say('Jsi v cíli.');
      return openSheet(`<button class="x" data-act="close" aria-label="Zavřít">×</button><h2 class="ttl">⚑ Jsi v cíli</h2><p class="sub">${esc(place.name)}</p>`);
    }
    const next = route.steps.find(s => s.idx >= idx) || route.steps[route.steps.length - 1];
    const toNext = Math.max(0, route.cum[next.idx] - done);
    const secs = total ? route.dur * left / total : 0;

    // hlasové pokyny: s předstihem a těsně před odbočkou
    if (me) {
      const v = me.speed || 0, pre = v > 22 ? 1000 : v > 12 ? 500 : 250, now = v > 22 ? 150 : v > 12 ? 70 : 35;
      if (toNext <= now) { if (!spoken.has(next.idx + 'n')) { spoken.add(next.idx + 'n'); spoken.add(next.idx + 'p'); say(next.text + '.'); } }
      else if (toNext <= pre && toNext > now * 2 && !spoken.has(next.idx + 'p')) { spoken.add(next.idx + 'p'); say(`Za ${sayDist(toNext)} ${lcFirst(next.text)}.`); }
    }
    const cam = mode === 'car' && route.cams && route.cams.find(c => c.d > done + 20);
    box.innerHTML = `<div class="navTop"><div class="ar">${next.icon}</div>
        <div><div class="dist">${me ? fmtDist(toNext) : '…'}</div><div class="ins">${esc(next.text)}</div></div></div>
      ${cam ? `<div class="badges"><span class="badge warn">${cam.kind === 'light' ? '🚦' : '📷'} ${CAM[cam.kind].label} za ${fmtDist(cam.d - done)}${cam.limit ? ` · limit ${cam.limit}` : ''}</span></div>` : ''}
      <div class="row" style="align-items:center">
        <div style="flex:1;min-width:0"><b style="font-size:18px">${fmtClock(new Date(Date.now() + secs * 1000))}</b>
          <span class="sub"> · ${fmtDur(secs)} · ${fmtDist(left)}</span></div>
        <button class="btn danger" data-act="stop">Ukončit</button></div>
      ${me ? '' : '<p class="sub">Čekám na signál polohy…</p>'}`;
  }

  // ====================================================================
  // Poloha, rychlost, limit, radar před sebou
  // ====================================================================
  function nearestRoad(p) {
    const moving = me && me.heading != null && (me.speed || 0) > 2;
    let best = null, bd = ROAD_SNAP_M;
    for (const r of roads.values()) {
      for (let i = 1; i < r.pts.length; i++) {
        const a = r.pts[i - 1], b = r.pts[i];
        if (Math.abs(a[0] - p[0]) > .01 || Math.abs(a[1] - p[1]) > .02) continue;
        let d = distToSeg(p, a, b);
        // silnice, která vede jiným směrem, než jedu (křižovatka, souběžná ulice), má menší váhu
        if (moving && d < bd) { const x = angDiff(me.heading, bearing(a[0], a[1], b[0], b[1])); if (Math.min(x, 180 - x) > 50) d += 25; }
        if (d < bd) { bd = d; best = r; }
      }
    }
    return best;
  }

  function updateDrive() {
    if (!me) return;
    $('hud').hidden = false;
    const road = nearestRoad([me.lat, me.lon]);
    $('limit').textContent = road ? road.limit : '–';
    $('limit').classList.toggle('unknown', !road);
    const kmh = me.speed != null ? Math.round(me.speed * 3.6) : null;
    $('speed').textContent = kmh != null ? kmh : '–';
    const over = !!(road && kmh != null && kmh > road.limit + 3);
    $('hud').classList.toggle('over', over);
    if (over && overArmed) { overArmed = false; beep(520, 1); }
    else if (road && kmh != null && kmh <= road.limit) overArmed = true;

    // nejbližší radar – za jízdy jen ten, který mám před sebou
    const moving = me.heading != null && (me.speed || 0) > 3;
    let near = null, nd = RADAR_WARN_M;
    for (const [id, c] of cams) {
      const d = dist(me.lat, me.lon, c.lat, c.lon);
      if (d > RADAR_WARN_M * 2) { warned.delete(id); continue; }
      if (d >= nd || c.end) continue;
      if (moving && d > 60 && angDiff(me.heading, bearing(me.lat, me.lon, c.lat, c.lon)) > 75) continue;
      nd = d; near = { id, ...c };
    }
    const al = $('alert');
    if (near && on('tRadar')) {
      al.textContent = `${near.kind === 'light' ? '🚦' : '📷'} ${CAM[near.kind].label} za ${Math.round(nd / 10) * 10} m` + (near.limit ? ` · limit ${near.limit}` : '');
      al.classList.add('show');
      if (!warned.has(near.id)) {
        warned.add(near.id); beep();
        say(`Pozor, ${CAM[near.kind].say} za ${sayDist(nd)}` + (near.limit ? `, limit ${near.limit}.` : '.'));
      }
    } else al.classList.remove('show');
  }

  function onPos(pos) {
    const c = pos.coords;
    let speed = c.speed;
    if ((speed == null || isNaN(speed)) && prev) {       // dopočet, když zařízení rychlost neposílá
      const dt = (pos.timestamp - prev.t) / 1000;
      if (dt > .5 && dt < 15) speed = dist(prev.lat, prev.lon, c.latitude, c.longitude) / dt;
    }
    if (speed == null || isNaN(speed) || speed < 0 || speed > 90) speed = null;     // přes 320 km/h je skok GPS, ne jízda
    if (c.heading != null && !isNaN(c.heading) && (speed || 0) > 1) lastHeading = c.heading;
    else if (prev && dist(prev.lat, prev.lon, c.latitude, c.longitude) > 8) lastHeading = bearing(prev.lat, prev.lon, c.latitude, c.longitude);
    if (!prev || dist(prev.lat, prev.lon, c.latitude, c.longitude) > 8 || pos.timestamp - prev.t > 4000) prev = { lat: c.latitude, lon: c.longitude, t: pos.timestamp };
    me = { lat: c.latitude, lon: c.longitude, speed, heading: lastHeading };

    const ll = [me.lat, me.lon];
    if (!meMarker) {
      meMarker = L.marker(ll, { icon: L.divIcon({ className: '', html: '<div class="me"><i></i></div>', iconSize: [24, 24], iconAnchor: [12, 12] }), zIndexOffset: 1000, interactive: false }).addTo(map);
      accCircle = L.circle(ll, { radius: c.accuracy, color: '#c084fc', weight: 1, fillOpacity: .08, interactive: false }).addTo(map);
    } else { meMarker.setLatLng(ll); accCircle.setLatLng(ll).setRadius(c.accuracy); }
    const dot = meMarker.getElement() && meMarker.getElement().firstChild;
    if (dot) {
      dot.classList.toggle('dir', me.heading != null);
      dot.style.transform = me.heading != null ? `rotate(${me.heading}deg)` : '';
    }
    if (follow) map.setView(ll, Math.max(map.getZoom(), nav ? 17 : 16), { animate: true });
    else if (autoCenter && !place && !route) map.setView(ll, 15);     // první zjištění polohy po načtení stránky
    autoCenter = false;
    updateDrive();
    updateNav();
  }

  function setFollow(v) {
    follow = v;
    $('locate').classList.toggle('on', v);
    if (v && me) map.setView([me.lat, me.lon], Math.max(map.getZoom(), 16));
  }
  function locHelp() {
    openSheet(`<button class="x" data-act="close" aria-label="Zavřít">×</button>
      <h2 class="ttl">📍 Poloha je vypnutá</h2>
      <p class="sub">Prohlížeč stránce polohu nepovolil, proto se na ni ani nezeptal. Zapneš ji takhle:</p>
      <ul class="info">
        <li><span>1.</span><span>Máš-li stránku otevřenou uvnitř jiné aplikace, otevři ji přímo v <b>Safari</b> (ikona kompasu dole).</span></li>
        <li><span>2.</span><span><b>Nastavení → Soukromí a zabezpečení → Polohové služby</b>: zapni je a u položky <b>Safari</b> zvol „Při používání aplikace“.</span></li>
        <li><span>3.</span><span>V Safari klepni vlevo v adresním řádku na ikonu stránky → <b>Nastavení webu → Poloha → Povolit</b>.</span></li>
        <li><span>4.</span><span>Vrať se sem a klepni na „Zkusit znovu“.</span></li>
      </ul>
      <div class="row"><button class="btn primary" data-act="locRetry">Zkusit znovu</button></div>`);
  }
  function startWatch() {
    if (watchId != null) return;
    if (!navigator.geolocation) { $('locAsk').hidden = true; return setStatus('Tento prohlížeč neumí zjistit polohu.', true); }
    clearTimeout(askTimer);
    // když se prohlížeč do pár vteřin nezeptá ani nepošle polohu, nabídneme tlačítko – dotyk dotaz spolehlivě vyvolá
    askTimer = setTimeout(() => { if (!me) $('locAsk').hidden = false; }, 3500);
    watchId = navigator.geolocation.watchPosition(pos => {
      if (!me) setStatus('');
      $('locAsk').hidden = true; locDenied = false;
      onPos(pos);
    }, err => {
      if (!me) $('locAsk').hidden = false;
      if (err.code === 1) {
        navigator.geolocation.clearWatch(watchId); watchId = null; setFollow(false);
        setStatus('Poloha není povolená.', true);
        if (locDenied || (!place && !route && !cat)) locHelp();
        locDenied = true;
      } else if (!me) setStatus('Polohu se zatím nedaří zjistit. Zkus to venku nebo zapni Wi‑Fi.', true);
    }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 });
  }
  function askLocation() {
    if (watchId != null) { navigator.geolocation.clearWatch(watchId); watchId = null; }
    setStatus('Zjišťuji polohu…');
    autoCenter = !place && !route;
    startWatch();
    if (watchId != null) setFollow(true);
  }
  $('locAsk').addEventListener('click', askLocation);
  $('locate').addEventListener('click', () => {
    if (!navigator.geolocation) return setStatus('Tento prohlížeč neumí zjistit polohu.', true);
    if (follow) return setFollow(false);
    if (!me) setStatus('Zjišťuji polohu…');
    startWatch(); setFollow(true);
  });
  map.on('dragstart', () => { setFollow(false); autoCenter = false; });

  // ====================================================================
  // Start
  // ====================================================================
  // sdílený odkaz na místo: #p=lat,lon,název
  const shared = /^#p=(-?\d+(?:\.\d+)?),(-?\d+(?:\.\d+)?)(?:,(.*))?$/.exec(location.hash);
  if (shared) {
    let name = 'Sdílené místo';
    try { if (shared[3]) name = decodeURIComponent(shared[3]); } catch { /* ponecháme výchozí název */ }
    autoCenter = false;
    showPlace({ lat: +shared[1], lon: +shared[2], name, sub: `${(+shared[1]).toFixed(5)}, ${(+shared[2]).toFixed(5)}` }, true);
  }

  startWatch();     // stránka si hned po načtení sama řekne o povolení polohy
  refresh();

  if ('serviceWorker' in navigator && /^https?:$/.test(location.protocol)) {
    addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => { /* bez offline režimu */ }));
  }
})();
