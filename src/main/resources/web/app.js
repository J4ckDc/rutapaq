// RUTAPAQ v0.5.0 – visualizador del Simulador de rutas: simulación de 5 días y hasta el colapso, con averías de las unidades.
// v0.5: semáforo + vacío, filtros globales, indicadores globales, pestañas Unidades y Almacenes, envíos con productos,
// ícono orientado según el movimiento, zoom continuo y reporte de la última planificación estable.
// Recibe el estado completo por SSE (/api/stream) cada ~500 ms y lo dibuja en un canvas.
// Toda la lógica (simulación, planificación, semáforo) está en el servidor: aquí solo se dibuja.
// Entre una foto y la siguiente, las unidades se interpolan siguiendo su camino por las calles,
// para que se desplacen de forma continua y sin saltos.
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  // v0.5 (B01): tamaño del mapa en km; lo fija el servidor (ien.mapaAnchoKm x ien.mapaAltoKm), ver fijarMapa
  let ANCHO = 70, ALTO = 50;
  const MARGEN = 34;
  const cv = $('mapa');
  const ctx = cv.getContext('2d');

  let estado = null;            // última foto recibida
  let anterior = null;          // foto previa (para interpolar)
  let recibidoEn = 0;           // performance.now() de la última foto
  let intervalo = 500;          // ms entre fotos (se mide)
  let col = {};                 // colores leídos de las variables CSS
  let sel = null;               // {tipo: 'unidad'|'almacen'|'pedido'|'bloqueo', id}
  let sucio = true;             // hay que redibujar
  let dibujadas = [];           // unidades dibujadas (para detectar el mouse)
  let finMostrado = '';
  let errorMostrado = '';
  let lineasAvisadas = '';
  const anim = new Map();       // id de unidad -> animación entre dos fotos
  // v04: modo elegido en la barra superior (SEMANAL = "5 Días" o COLAPSO). Sigue a la simulación en curso cuando
  // aparece una nueva (iniciada aquí o en otro dispositivo); el usuario lo cambia para la próxima que inicie.
  let modo = 'SEMANAL';
  let escenarioVisto = '';
  let alertaColapso = null;     // alerta roja del colapso: no se cierra sola
  const vista = { zoom: 1, ox: 0, oy: 0 };
  // v0.5: zoom continuo (sin saltos): el mapa se acerca o aleja de a pocos hacia el objetivo
  const zoomAnim = { activo: false, modo: 'ancla', z: 1, px: 0, py: 0, mx: 0, my: 0, ox: 0, oy: 0 };
  const ZOOM_MIN = 0.8, ZOOM_MAX = 14;
  // v0.5: filtros globales del mapa (D05 a D07); por defecto se ve todo
  const filtros = { tipo: new Set(['AUTO', 'MOTO', 'BICI']), carga: new Set(['VERDE', 'AMBAR', 'ROJO', 'VACIO']),
    almacen: new Set(['VERDE', 'AMBAR', 'ROJO', 'VACIO']) };
  const pasaUnidad = (u) => filtros.tipo.has(u.tipo) && filtros.carga.has(u.nivel);
  const pasaAlmacen = (a) => a.central || filtros.almacen.has(a.nivel);
  // v0.5: umbrales del semáforo (de /api/config), para que las leyendas coincidan con la configuración
  let umbrales = { plazo: { verde: 95, ambar: 85 }, almacen: { verde: 30, ambar: 10 }, carga: { verde: 50, ambar: 20 },
    flota: { verde: 50, ambar: 20 }, pedido: { verde: 0.5, ambar: 0.2 } };
  const orient = new Map();     // v0.5: última orientación de cada unidad (para que no gire al detenerse)

  // ================================================================ utilidades
  const num = (v) => Number(v).toLocaleString('es-PE');
  const dos = (v) => String(v).padStart(2, '0');
  const esc = (s) => String(s).replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  function fechaLocal(iso) {               // "2026-01-01T09:30:15" -> Date en hora local
    const m = /^(\d+)-(\d+)-(\d+)T(\d+):(\d+)(?::(\d+))?/.exec(iso || '');
    return m ? new Date(+m[1], m[2] - 1, +m[3], +m[4], +m[5], +(m[6] || 0)) : null;
  }
  const fmtFecha = (d) => d ? `${dos(d.getDate())}/${dos(d.getMonth() + 1)}/${d.getFullYear()} ${dos(d.getHours())}:${dos(d.getMinutes())}:${dos(d.getSeconds())}` : '--';
  const fmtCorta = (iso) => { const d = fechaLocal(iso); return d ? `${dos(d.getDate())}/${dos(d.getMonth() + 1)} ${dos(d.getHours())}:${dos(d.getMinutes())}` : '--'; };
  function fmtDur(seg) {
    seg = Math.max(0, Math.floor(seg));
    const d = Math.floor(seg / 86400), h = Math.floor(seg % 86400 / 3600), m = Math.floor(seg % 3600 / 60), s = seg % 60;
    return (d ? `${d}d ` : '') + `${dos(h)}:${dos(m)}:${dos(s)}`;
  }
  const TIPOS = { AUTO: 'Auto', MOTO: 'Moto', BICI: 'Bicicleta' };
  const ESTADOS_U = { EN_RUTA: 'En ruta', ENTREGANDO: 'Entregando', REFRIGERIO: 'Refrigerio', DISPONIBLE: 'Sin tareas', MANTENIMIENTO: 'En mantenimiento',
    AVERIADA: 'Averiada', TRASVASE: 'Trasvase' };
  // v03: averías (P&R pregunta 3)
  const NOMBRE_TIPO_AV = { 1: 'menor', 2: 'intermedia', 3: 'mayor' };
  const FASES_AV = { EN_LUGAR: 'En el lugar', EN_CENTRAL: 'En el central', FINALIZADA: 'Finalizada' };
  const esTrasvase = (ref) => typeof ref === 'string' && ref.startsWith('TRV-');
  // v0.4.1: stock de un almacén intermedio en % de su capacidad. Se trunca para que el número coincida con su color
  // (con el umbral de 30 %, un 29.6 % ya es ámbar y se muestra 29 %).
  const pctStock = (a) => Math.floor(Math.max(0, Math.min(100, a.stockPct)));
  const textoStock = (a) => `${a.stock} de ${a.capacidad} paquetes (capacidad máxima)`;
  const CASA = '<svg viewBox="0 0 24 24" class="casa-mini" aria-hidden="true"><circle cx="12" cy="12" r="10" fill="var(--fondo)" stroke="currentColor" stroke-width="2.4"/><path d="M12 6.5l6 5.2h-1.6V17H7.6v-5.3H6z" fill="currentColor" stroke="none"/></svg>';
  /** Unidad en camino hacia una averiada para el trasvase: su próxima parada es la recogida en ese punto. */
  const vaATrasvasar = (u) => u.estado === 'EN_RUTA' && u.paradas.length > 0
    && u.paradas[0].tipo === 'RECARGA' && esTrasvase(u.paradas[0].ref);
  const pedidosReplanificados = (n) => n === 1 ? 'su pedido se replanifica' : `sus ${n} pedidos se replanifican`;
  const ESTADOS_SIM = { SIN_SIMULACION: 'Sin simulación', PREPARADA: 'Preparando', EJECUTANDO: 'Simulando', PAUSADA: 'Detenido', TERMINADA: 'Finalizado',
    COLAPSO: 'Colapso' };
  const esColapso = () => modo === 'COLAPSO';

  // ================================================================ tema claro / oscuro
  function leerColores() {
    const s = getComputedStyle(document.documentElement);
    for (const k of ['fondo', 'superficie', 'borde', 'texto', 'texto-2', 'texto-3', 'cian', 'naranja', 'verde', 'ambar', 'rojo', 'violeta', 'gris',
      'mapa-calle', 'mapa-manzana', 'mapa-eje', 'mapa-texto']) col[k] = s.getPropertyValue('--' + k).trim();
    col.VERDE = col.verde; col.AMBAR = col.ambar; col.ROJO = col.rojo; col.NEUTRO = col['texto-2']; col.VACIO = col.gris;
    cacheBase = null;
    sucio = true;
  }
  function aplicarTema(t) {
    document.documentElement.dataset.theme = t;
    $('tema').setAttribute('aria-label', t === 'dark' ? 'Cambiar a tema claro' : 'Cambiar a tema oscuro');
    try { localStorage.setItem('rutapaq.tema', t); } catch (e) { /* sin almacenamiento: no pasa nada */ }
    leerColores();
  }
  $('tema').onclick = () => aplicarTema(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark');

  // ================================================================ coordenadas del mapa
  function geom() {
    const w = cv.clientWidth, h = cv.clientHeight;
    const base = Math.min((w - 2 * MARGEN) / ANCHO, (h - 2 * MARGEN) / ALTO);
    const s = base * vista.zoom;
    return { w, h, s, cx: (w - ANCHO * s) / 2 + vista.ox, cy: (h - ALTO * s) / 2 + vista.oy };
  }
  function aPantalla(x, y, g = geom()) { return [g.cx + x * g.s, g.cy + (ALTO - y) * g.s]; }
  function aMundo(px, py, g = geom()) { return [(px - g.cx) / g.s, ALTO - (py - g.cy) / g.s]; }

  function ajustarTamano() {
    const dpr = window.devicePixelRatio || 1;
    cv.width = Math.round(cv.clientWidth * dpr);
    cv.height = Math.round(cv.clientHeight * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    cacheBase = null;
    sucio = true;
  }

  // ================================================================ capa base: manzanas y calles (en caché)
  let cacheBase = null, claveBase = '';
  function capaBase(g) {
    const dpr = window.devicePixelRatio || 1;
    const clave = [g.w, g.h, g.s.toFixed(3), g.cx.toFixed(1), g.cy.toFixed(1), dpr, document.documentElement.dataset.theme].join('|');
    if (cacheBase && clave === claveBase) return cacheBase;
    const c = document.createElement('canvas');
    c.width = Math.round(g.w * dpr); c.height = Math.round(g.h * dpr);
    const k = c.getContext('2d');
    k.setTransform(dpr, 0, 0, dpr, 0, 0);
    k.fillStyle = col.fondo; k.fillRect(0, 0, g.w, g.h);
    const [x0, y0] = aPantalla(0, ALTO, g), [x1, y1] = aPantalla(ANCHO, 0, g);
    k.fillStyle = col['mapa-calle']; k.fillRect(x0, y0, x1 - x0, y1 - y0);
    // manzanas: una por km² (si están muy pequeñas, se agrupan de 5 en 5)
    const paso = g.s >= 7 ? 1 : 5;
    const calle = Math.max(1, Math.min(g.s * paso * 0.16, 7));
    k.fillStyle = col['mapa-manzana'];
    const ix0 = Math.max(0, Math.floor(aMundo(0, 0, g)[0] / paso) * paso), ix1 = Math.min(ANCHO, Math.ceil(aMundo(g.w, 0, g)[0]));
    const iy0 = Math.max(0, Math.floor(aMundo(0, g.h, g)[1] / paso) * paso), iy1 = Math.min(ALTO, Math.ceil(aMundo(0, 0, g)[1]));
    for (let x = ix0; x < ix1; x += paso) {
      for (let y = iy0; y < iy1; y += paso) {
        const [px, py] = aPantalla(x, y + paso, g);
        k.fillRect(px + calle / 2, py + calle / 2, g.s * paso - calle, g.s * paso - calle);
      }
    }
    // ejes cada 10 km
    k.fillStyle = col['mapa-texto'];
    k.font = '11px "Cascadia Mono", Consolas, monospace';
    k.textAlign = 'center';
    for (let x = 0; x <= ANCHO; x += 10) { const [px, py] = aPantalla(x, 0, g); k.fillText(x, px, py + 16); }
    k.textAlign = 'right';
    for (let y = 10; y <= ALTO; y += 10) { const [px, py] = aPantalla(0, y, g); k.fillText(y, px - 7, py + 4); }
    k.strokeStyle = col['mapa-eje']; k.lineWidth = 1;
    k.strokeRect(x0 + .5, y0 + .5, x1 - x0, y1 - y0);
    cacheBase = c; claveBase = clave;
    return c;
  }

  // ================================================================ animación de las unidades
  // La unidad avanza desde su posición mostrada hasta la nueva siguiendo los nodos de su camino.
  function prepararAnimacion() {
    const ahora = performance.now();
    const previas = new Map((anterior?.unidades || []).map((u) => [u.id, u]));
    for (const u of estado.unidades) {
      const ini = posicionUnidad(u.id, ahora) || [u.x, u.y];
      const prev = previas.get(u.id);
      let ruta = [[u.x, u.y]];
      const pol = [ini, ...(prev ? prev.camino : [])];
      for (let i = 0; i + 1 < pol.length; i++) {
        if (enSegmento([u.x, u.y], pol[i], pol[i + 1])) { ruta = [...pol.slice(0, i + 1), [u.x, u.y]]; break; }
      }
      if (ruta.length === 1 && Math.hypot(ini[0] - u.x, ini[1] - u.y) < 3) ruta = [ini, [u.x, u.y]];
      const largos = [0];
      for (let i = 1; i < ruta.length; i++) largos.push(largos[i - 1] + Math.abs(ruta[i][0] - ruta[i - 1][0]) + Math.abs(ruta[i][1] - ruta[i - 1][1]));
      anim.set(u.id, { ruta, largos, t0: ahora });
    }
  }
  function enSegmento(p, a, b) {
    const e = 1e-6;
    return Math.min(a[0], b[0]) - e <= p[0] && p[0] <= Math.max(a[0], b[0]) + e
      && Math.min(a[1], b[1]) - e <= p[1] && p[1] <= Math.max(a[1], b[1]) + e
      && Math.abs((b[0] - a[0]) * (p[1] - a[1]) - (b[1] - a[1]) * (p[0] - a[0])) < 1e-6;
  }
  function posicionUnidad(id, ahora) {
    const a = anim.get(id);
    if (!a) return null;
    const total = a.largos[a.largos.length - 1];
    const f = estado && estado.estado === 'EJECUTANDO' ? Math.min(1, (ahora - a.t0) / intervalo) : 1;
    if (total === 0 || f >= 1) return a.ruta[a.ruta.length - 1];
    const d = total * f;
    let i = 1;
    while (i < a.largos.length - 1 && a.largos[i] < d) i++;
    const s0 = a.largos[i - 1], s1 = a.largos[i], r = s1 > s0 ? (d - s0) / (s1 - s0) : 1;
    const p = a.ruta[i - 1], q = a.ruta[i];
    return [p[0] + (q[0] - p[0]) * r, p[1] + (q[1] - p[1]) * r];
  }
  /** v0.5: dirección (dx, dy en km) del tramo que la unidad recorre en la animación; null si está quieta. */
  function direccionAnim(id, ahora) {
    const a = anim.get(id);
    if (!a) return null;
    const total = a.largos[a.largos.length - 1];
    const f = estado && estado.estado === 'EJECUTANDO' ? Math.min(1, (ahora - a.t0) / intervalo) : 1;
    if (total === 0 || f >= 1) return null;
    const d = total * f;
    let i = 1;
    while (i < a.largos.length - 1 && a.largos[i] < d) i++;
    const p = a.ruta[i - 1], q = a.ruta[i];
    return [q[0] - p[0], q[1] - p[1]];
  }
  /**
   * v0.5: hacia dónde mira la unidad (rúbrica C30): de lado hacia la derecha o la izquierda cuando avanza en el eje X,
   * y girada hacia arriba o hacia abajo cuando avanza en el eje Y. Detenida, conserva su última orientación.
   */
  function orientacion(u, pos, ahora) {
    let d = direccionAnim(u.id, ahora);
    if (!d && u.camino.length) {
      for (const n of u.camino) {
        const dx = n[0] - pos[0], dy = n[1] - pos[1];
        if (Math.abs(dx) + Math.abs(dy) > 1e-6) { d = [dx, dy]; break; }
      }
    }
    if (d && Math.abs(d[0]) + Math.abs(d[1]) > 1e-6) {
      orient.set(u.id, Math.abs(d[0]) >= Math.abs(d[1]) ? { sentido: d[0] < 0 ? -1 : 1, rot: 0 }
        : { sentido: 1, rot: d[1] > 0 ? -Math.PI / 2 : Math.PI / 2 });    // en pantalla el norte está arriba
    }
    return orient.get(u.id) || { sentido: 1, rot: 0 };
  }

  // ================================================================ dibujo
  function dibujar() {
    const g = geom();
    ctx.clearRect(0, 0, g.w, g.h);
    ctx.drawImage(capaBase(g), 0, 0, g.w, g.h);
    if (!estado || !estado.unidades) return;
    const ahora = performance.now();
    const posiciones = new Map(estado.unidades.map((u) => [u.id, posicionUnidad(u.id, ahora) || [u.x, u.y]]));

    dibujarBloqueos(g);
    dibujarRutas(g, posiciones);
    dibujarPedidos(g);
    dibujarAlmacenes(g);
    dibujarUnidades(g, posiciones);
  }

  function dibujarBloqueos(g) {
    const ancho = Math.max(3, Math.min(g.s * 0.3, 7));
    estado.bloqueos.forEach((b, i) => {
      const elegido = sel && sel.tipo === 'bloqueo' && sel.id === i;
      ctx.lineCap = 'round'; ctx.lineJoin = 'round';
      if (elegido) { ctx.strokeStyle = col.rojo + '55'; ctx.lineWidth = ancho + 8; trazar(b.vertices, g); }
      ctx.strokeStyle = col.rojo; ctx.lineWidth = ancho;
      trazar(b.vertices, g);
      const v = b.vertices[Math.floor((b.vertices.length - 1) / 2)];
      const w = b.vertices[Math.min(b.vertices.length - 1, Math.floor((b.vertices.length - 1) / 2) + 1)];
      const [px, py] = aPantalla((v[0] + w[0]) / 2, (v[1] + w[1]) / 2, g);
      triangulo(px, py - 12, 12);
    });
  }
  function trazar(pts, g) {
    ctx.beginPath();
    pts.forEach((p, i) => { const [x, y] = aPantalla(p[0], p[1], g); i ? ctx.lineTo(x, y) : ctx.moveTo(x, y); });
    ctx.stroke();
  }
  function triangulo(x, y, r) {
    ctx.beginPath();
    ctx.moveTo(x, y - r); ctx.lineTo(x + r * 0.95, y + r * 0.7); ctx.lineTo(x - r * 0.95, y + r * 0.7); ctx.closePath();
    ctx.fillStyle = col.rojo + '33'; ctx.fill();
    ctx.strokeStyle = col.rojo; ctx.lineWidth = 1.6; ctx.stroke();
    ctx.fillStyle = col.rojo; ctx.font = 'bold 11px system-ui'; ctx.textAlign = 'center';
    ctx.fillText('!', x, y + r * 0.45);
  }

  /** Unidades a resaltar según lo seleccionado. */
  function unidadesResaltadas() {
    if (!sel || !estado) return new Set();
    if (sel.tipo === 'unidad') {        // v03: con una averiada se resaltan también las que vienen a trasvasar
      return new Set([sel.id, ...ayudantes(averiaDe(estado.unidades.find((x) => x.id === sel.id)))]);
    }
    if (sel.tipo === 'pedido') {
      if (sel.unidad) return new Set([sel.unidad]);       // v0.5: producto o envío elegido: solo su unidad (F02)
      const p = estado.pedidos.find((x) => x.id === sel.id); return new Set(p ? p.unidades : []);
    }
    if (sel.tipo === 'almacen') { const a = estado.almacenes.find((x) => x.id === sel.id); return new Set(a ? a.recargan : []); }
    return new Set();
  }

  /**
   * Modo enfoque: al elegir una unidad solo se dibuja esa unidad (y se atenúan los pedidos que no lleva);
   * al elegir un pedido solo se dibujan las unidades que lo atienden en este momento.
   * Devuelve null cuando no hay enfoque (se dibuja todo).
   */
  function enfoque() {
    if (!sel || !estado || !estado.unidades) return null;
    if (sel.tipo === 'unidad') {
      const u = estado.unidades.find((x) => x.id === sel.id);
      if (!u) return null;
      return { unidades: new Set([u.id, ...ayudantes(averiaDe(u))]), pedidos: new Set(u.paradas.filter((p) => p.tipo === 'ENTREGA').map((p) => p.ref)) };
    }
    if (sel.tipo === 'pedido') {
      const p = estado.pedidos.find((x) => x.id === sel.id);
      if (!p) return null;                       // ya se entregó: vuelve la vista completa
      return { unidades: new Set(sel.unidad ? [sel.unidad] : p.unidades), pedidos: new Set([p.id]) };
    }
    return null;
  }

  function dibujarRutas(g, posiciones) {
    const resaltadas = unidadesResaltadas();
    const hayFoco = resaltadas.size > 0;
    const enf = enfoque();
    ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    // tramo hacia la próxima parada de cada unidad (lo ya recorrido no se dibuja)
    for (const u of estado.unidades) {
      if (!u.camino.length) continue;
      if (enf && !enf.unidades.has(u.id)) continue;
      const pos = posiciones.get(u.id);
      const foco = resaltadas.has(u.id);
      ctx.setLineDash([6, 5]);
      ctx.strokeStyle = foco ? col.cian : hexA(col.cian, hayFoco ? 0.18 : 0.55);
      ctx.lineWidth = foco ? 2.6 : 1.6;
      trazar([pos, ...restoCamino(u, pos)], g);
    }
    ctx.setLineDash([]);
    // itinerario completo de las unidades resaltadas
    for (const u of estado.unidades) {
      if (!resaltadas.has(u.id) || !u.paradas.length) continue;
      const desde = u.camino.length ? u.camino[u.camino.length - 1] : posiciones.get(u.id);
      let prev = desde;
      ctx.setLineDash([3, 5]); ctx.strokeStyle = hexA(col.cian, 0.75); ctx.lineWidth = 1.6;
      u.paradas.forEach((p, i) => {
        if (i === 0 && u.camino.length) { prev = [p.x, p.y]; return; }
        trazar([prev, [p.x, prev[1]], [p.x, p.y]], g);
        prev = [p.x, p.y];
      });
      ctx.setLineDash([]);
      let n = 0;
      u.paradas.forEach((p) => {
        const [px, py] = aPantalla(p.x, p.y, g);
        const trv = p.tipo === 'RECARGA' && esTrasvase(p.ref);
        const etiqueta = trv ? 'T' : p.tipo === 'RECARGA' ? 'R' : p.tipo === 'REFRIGERIO' ? 'C' : String(++n);
        ctx.fillStyle = trv ? col.rojo : p.tipo === 'REFRIGERIO' ? col.violeta : p.tipo === 'RECARGA' ? col.naranja : col.cian;
        ctx.beginPath(); ctx.arc(px, py - 13, 8, 0, 7); ctx.fill();
        ctx.fillStyle = col.fondo; ctx.font = 'bold 10px system-ui'; ctx.textAlign = 'center';
        ctx.fillText(etiqueta, px, py - 9.5);
      });
    }
  }
  function restoCamino(u, pos) {     // nodos del camino que aún no alcanza la posición interpolada
    const r = [];
    for (const n of u.camino) {
      if (r.length === 0 && Math.abs(n[0] - pos[0]) + Math.abs(n[1] - pos[1]) < 1e-6) continue;
      r.push(n);
    }
    return r.length ? r : u.camino;
  }

  function dibujarPedidos(g) {
    const r = Math.max(2.5, Math.min(5, g.s * 0.28));
    const enf = enfoque();
    for (const p of estado.pedidos) {
      const [px, py] = aPantalla(p.x, p.y, g);
      if (px < -10 || py < -10 || px > g.w + 10 || py > g.h + 10) continue;
      ctx.globalAlpha = enf && !enf.pedidos.has(p.id) ? 0.18 : 1;   // en enfoque, los demás pedidos se atenúan
      ctx.fillStyle = col[p.nivel] || col['texto-2'];
      ctx.beginPath(); ctx.arc(px, py, r, 0, 7); ctx.fill();
      if (p.vencido) { ctx.strokeStyle = col.rojo; ctx.lineWidth = 1.5; ctx.beginPath(); ctx.arc(px, py, r + 3, 0, 7); ctx.stroke(); }
    }
    ctx.globalAlpha = 1;
    if (sel && sel.tipo === 'pedido') {
      const p = estado.pedidos.find((x) => x.id === sel.id);
      if (p) {
        const [px, py] = aPantalla(p.x, p.y, g);
        ctx.strokeStyle = col.cian; ctx.lineWidth = 2.5;
        ctx.beginPath(); ctx.arc(px, py, r + 7, 0, 7); ctx.stroke();
      }
    }
  }

  function dibujarAlmacenes(g) {
    for (const a of estado.almacenes) {
      if (!pasaAlmacen(a) && !(sel && sel.tipo === 'almacen' && sel.id === a.id)) continue;   // v0.5: filtro D07
      const [px, py] = aPantalla(a.x, a.y, g);
      const c = a.central ? col.naranja : col[a.nivel];
      const R = 17;
      ctx.fillStyle = hexA(c, 0.16); ctx.beginPath(); ctx.arc(px, py, R + 8, 0, 7); ctx.fill();
      ctx.fillStyle = col.fondo; ctx.beginPath(); ctx.arc(px, py, R, 0, 7); ctx.fill();
      ctx.strokeStyle = c; ctx.lineWidth = sel && sel.tipo === 'almacen' && sel.id === a.id ? 4.5 : 3;
      ctx.beginPath(); ctx.arc(px, py, R, 0, 7); ctx.stroke();
      // casita
      ctx.fillStyle = c;
      ctx.beginPath();
      ctx.moveTo(px, py - 9); ctx.lineTo(px + 9, py - 1); ctx.lineTo(px + 6.5, py - 1); ctx.lineTo(px + 6.5, py + 7);
      ctx.lineTo(px - 6.5, py + 7); ctx.lineTo(px - 6.5, py - 1); ctx.lineTo(px - 9, py - 1); ctx.closePath(); ctx.fill();
      ctx.fillStyle = col.fondo; ctx.fillRect(px - 2, py + 1.5, 4, 5.5);
      // etiqueta
      const texto = a.central ? a.nombre : `${a.nombre} · ${pctStock(a)}%`;
      ctx.font = '600 12px "Segoe UI", system-ui';
      const w = ctx.measureText(texto).width + 16;
      ctx.fillStyle = hexA(col.superficie, 0.92); ctx.strokeStyle = col.borde; ctx.lineWidth = 1;
      redondeado(px - w / 2, py + R + 6, w, 21, 6); ctx.fill(); ctx.stroke();
      ctx.fillStyle = col.texto; ctx.textAlign = 'center';
      ctx.fillText(texto, px, py + R + 21);
    }
  }
  function redondeado(x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y); ctx.arcTo(x + w, y, x + w, y + h, r); ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r); ctx.arcTo(x, y, x + w, y, r); ctx.closePath();
  }

  function dibujarUnidades(g, posiciones) {
    const resaltadas = unidadesResaltadas();
    // las unidades que están en el mismo punto se dibujan una vez, con la cantidad
    const grupos = new Map();
    const enf = enfoque();
    for (const u of estado.unidades) {
      if (enf && !enf.unidades.has(u.id)) continue;   // en enfoque solo se ven las unidades elegidas
      if (!pasaUnidad(u) && !resaltadas.has(u.id)) continue;   // v0.5: filtros globales D05 y D06
      const p = posiciones.get(u.id);
      const averiada = u.estado === 'AVERIADA';       // v03: las averiadas se agrupan aparte
      const k = Math.round(p[0] * 20) + ':' + Math.round(p[1] * 20) + (averiada ? ':av' : '');
      if (!grupos.has(k)) grupos.set(k, { pos: p, unidades: [], averiada });
      grupos.get(k).unidades.push(u);
    }
    dibujadas = [];
    for (const gr of grupos.values()) {
      const u = gr.unidades.find((x) => resaltadas.has(x.id)) || gr.unidades[0];
      let [px, py] = aPantalla(gr.pos[0], gr.pos[1], g);
      if (gr.averiada && enAlmacen(gr.pos)) { px -= 26; py -= 20; }   // en el almacén central: junto a su ícono
      dibujadas.push({ px, py, unidades: gr.unidades });
      const foco = gr.unidades.some((x) => resaltadas.has(x.id));
      if (gr.averiada) { dibujarAveriada(gr, u, px, py, foco); continue; }
      const c = u.estado === 'MANTENIMIENTO' ? col.gris : u.estado === 'REFRIGERIO' ? col.violeta : col.cian;
      ctx.fillStyle = hexA(col.fondo, 0.78);
      ctx.beginPath(); ctx.arc(px, py, foco ? 14 : 11.5, 0, 7); ctx.fill();
      if (foco) { ctx.strokeStyle = col.cian; ctx.lineWidth = 2; ctx.stroke(); }
      if (u.estado === 'ENTREGANDO' || u.estado === 'TRASVASE') {   // anillo punteado: entrega (1 h) o trasvase (v03)
        ctx.setLineDash([2.5, 3]); ctx.strokeStyle = u.estado === 'TRASVASE' ? col.naranja : col.cian; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.arc(px, py, foco ? 18 : 15, 0, 7); ctx.stroke(); ctx.setLineDash([]);
      } else if (vaATrasvasar(u)) {   // anillo continuo: en camino a recoger paquetes de una averiada (v03)
        ctx.strokeStyle = col.naranja; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.arc(px, py, foco ? 18 : 15, 0, 7); ctx.stroke();
      }
      const o = orientacion(u, gr.pos, performance.now());
      glifo(u.tipo, px, py, c, o.sentido, foco ? 1.25 : 1, o.rot);
      // v0.5: barra de carga con semáforo + vacío bajo el ícono (C27); el color del ícono sigue siendo su estado
      if (gr.unidades.length === 1 && u.estado !== 'MANTENIMIENTO') barraCarga(u, px, py + (foco ? 16 : 13.5));
      if (gr.unidades.length > 1) {
        const t = String(gr.unidades.length);
        ctx.fillStyle = col.cian; ctx.beginPath(); ctx.arc(px + 11, py - 10, 8, 0, 7); ctx.fill();
        ctx.fillStyle = col.fondo; ctx.font = 'bold 10px system-ui'; ctx.textAlign = 'center'; ctx.fillText(t, px + 11, py - 6.5);
      }
      if (foco && sel && sel.tipo === 'unidad') {
        ctx.font = '600 12px "Cascadia Mono", Consolas, monospace'; ctx.fillStyle = col.texto; ctx.textAlign = 'left';
        ctx.fillText(sel.id, px + 16, py - 12);
      }
    }
  }

  /**
   * v03: unidad averiada, detenida en su posición, con un distintivo rojo y el tipo de avería (DS GUI, tabla 3).
   * Mientras sigue en el lugar la rodea un halo rojo; si hay varias juntas, el distintivo muestra cuántas son.
   */
  function dibujarAveriada(gr, u, px, py, foco) {
    // v0.4.1: un color por tipo de avería (rúbrica D11), el mismo de la pestaña Averías; rojo si hay tipos distintos
    const tipos = new Set(gr.unidades.map((x) => x.averia ? x.averia.tipo : 0));
    const cAv = tipos.size === 1 ? colorTipoAveria([...tipos][0]) : col.rojo;
    const enLugar = gr.unidades.some((x) => x.averia && x.averia.fase === 'EN_LUGAR');
    const latido = enLugar && estado.estado === 'EJECUTANDO' ? (Math.sin(performance.now() / 260) + 1) / 2 : 0;
    ctx.fillStyle = hexA(cAv, enLugar ? 0.14 + 0.12 * latido : 0.1);
    ctx.beginPath(); ctx.arc(px, py, (foco ? 19 : 16) + 3 * latido, 0, 7); ctx.fill();
    ctx.fillStyle = hexA(col.fondo, 0.86); ctx.beginPath(); ctx.arc(px, py, foco ? 14 : 11.5, 0, 7); ctx.fill();
    ctx.strokeStyle = foco ? col.cian : cAv; ctx.lineWidth = foco ? 2.2 : 1.6; ctx.stroke();
    glifo(u.tipo, px, py, col['texto-2'], 1, foco ? 1.25 : 1);
    // distintivo relleno con el tipo de avería; con varias averiadas juntas, de contorno y con la cantidad
    const varias = gr.unidades.length > 1;
    ctx.fillStyle = varias ? col.fondo : cAv; ctx.beginPath(); ctx.arc(px + 11, py - 10, 8, 0, 7); ctx.fill();
    ctx.strokeStyle = varias ? cAv : col.fondo; ctx.lineWidth = 1.5; ctx.stroke();
    ctx.fillStyle = varias ? cAv : '#fff'; ctx.font = 'bold 10px system-ui'; ctx.textAlign = 'center';
    ctx.fillText(varias ? String(gr.unidades.length) : String(u.averia ? u.averia.tipo : '!'), px + 11, py - 6.5);
    if (foco && sel && sel.tipo === 'unidad') {
      ctx.font = '600 12px "Cascadia Mono", Consolas, monospace'; ctx.fillStyle = col.texto; ctx.textAlign = 'left';
      ctx.fillText(sel.id, px + 21, py - 12);
    }
  }
  /** v0.5: barra de carga de una unidad: verde/ámbar/rojo según su carga; vacía, solo el contorno gris. */
  function barraCarga(u, x, y) {
    const w = 18, h = 3.5, f = u.capacidad ? Math.max(0, Math.min(1, u.carga / u.capacidad)) : 0;
    ctx.fillStyle = hexA(col.fondo, 0.85); ctx.fillRect(x - w / 2 - 1.5, y - 1.5, w + 3, h + 3);
    if (u.nivel === 'VACIO') {
      ctx.strokeStyle = col.gris; ctx.lineWidth = 1; ctx.strokeRect(x - w / 2 + 0.5, y + 0.5, w - 1, h - 1);
    } else {
      ctx.fillStyle = hexA(col.gris, 0.35); ctx.fillRect(x - w / 2, y, w, h);
      ctx.fillStyle = col[u.nivel] || col.gris; ctx.fillRect(x - w / 2, y, w * f, h);
    }
  }
  function colorTipoAveria(tipo) { return tipo === 1 ? col.ambar : tipo === 2 ? col.naranja : col.rojo; }
  function enAlmacen(pos) { return estado.almacenes.some((a) => Math.abs(a.x - pos[0]) < 0.01 && Math.abs(a.y - pos[1]) < 0.01); }

  /**
   * Ícono de auto, moto o bicicleta (vista lateral), mirando a la derecha (sentido 1) o a la izquierda (-1).
   * v0.5: rot (radianes) lo gira para avanzar hacia arriba o hacia abajo.
   */
  function glifo(tipo, x, y, color, sentido, esc, rot = 0) {
    ctx.save();
    ctx.translate(x, y); if (rot) ctx.rotate(rot); ctx.scale(sentido * esc, esc);
    ctx.strokeStyle = color; ctx.fillStyle = color; ctx.lineWidth = 1.6; ctx.lineJoin = 'round'; ctx.lineCap = 'round';
    ctx.shadowColor = color; ctx.shadowBlur = 6;
    if (tipo === 'AUTO') {
      ctx.beginPath();
      ctx.moveTo(-8, 2.5); ctx.lineTo(-8, -1.5); ctx.lineTo(-5, -2.5); ctx.lineTo(-3, -6); ctx.lineTo(3.5, -6); ctx.lineTo(6, -2.5); ctx.lineTo(8, -1.5); ctx.lineTo(8, 2.5); ctx.closePath();
      ctx.globalAlpha = .9; ctx.fill(); ctx.globalAlpha = 1;
      ctx.shadowBlur = 0; ctx.fillStyle = col.fondo;
      ctx.fillRect(-2.2, -5, 2.4, 2.4); ctx.fillRect(1, -5, 2.4, 2.4);
      ctx.beginPath(); ctx.arc(-4.5, 3, 2.3, 0, 7); ctx.fill(); ctx.stroke();
      ctx.beginPath(); ctx.arc(4.5, 3, 2.3, 0, 7); ctx.fill(); ctx.stroke();
    } else if (tipo === 'MOTO') {
      // moto: carrocería rellena (tanque, asiento y carenado) y ruedas gruesas con eje
      ctx.lineWidth = 2;
      ctx.beginPath(); ctx.arc(-5.5, 3, 2.5, 0, 7); ctx.stroke();
      ctx.beginPath(); ctx.arc(5.5, 3, 2.5, 0, 7); ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(-6.5, 0.5); ctx.lineTo(-4.5, -2.2); ctx.lineTo(-0.5, -2.8); ctx.lineTo(1.5, -4.2); ctx.lineTo(4, -4.2);
      ctx.lineTo(6, -0.8); ctx.lineTo(4, 1.6); ctx.lineTo(-2, 1.6); ctx.closePath(); ctx.fill();
      ctx.lineWidth = 1.6;
      ctx.beginPath(); ctx.moveTo(4.6, -1); ctx.lineTo(5.5, 3); ctx.moveTo(3, -4.2); ctx.lineTo(3.8, -6.6); ctx.lineTo(6, -6.6); ctx.stroke();
      ctx.shadowBlur = 0;
      ctx.beginPath(); ctx.arc(-5.5, 3, 0.9, 0, 7); ctx.fill();
      ctx.beginPath(); ctx.arc(5.5, 3, 0.9, 0, 7); ctx.fill();
    } else {
      ctx.lineWidth = 1.4;
      ctx.beginPath(); ctx.arc(-5, 2.5, 3.2, 0, 7); ctx.stroke();
      ctx.beginPath(); ctx.arc(5, 2.5, 3.2, 0, 7); ctx.stroke();
      ctx.beginPath(); ctx.moveTo(-5, 2.5); ctx.lineTo(-1, -2.5); ctx.lineTo(4, -2.5); ctx.lineTo(5, 2.5);
      ctx.moveTo(-1, -2.5); ctx.lineTo(0.5, 2.5); ctx.lineTo(-5, 2.5); ctx.moveTo(-2.5, -5); ctx.lineTo(0, -5);
      ctx.moveTo(4, -2.5); ctx.lineTo(3.5, -6); ctx.stroke();
    }
    ctx.restore();
  }

  function hexA(c, a) {             // "#22d3ee" + alfa
    if (!c || c[0] !== '#') return c;
    const h = c.length === 4 ? c.slice(1).split('').map((x) => x + x).join('') : c.slice(1, 7);
    return `rgba(${parseInt(h.slice(0, 2), 16)}, ${parseInt(h.slice(2, 4), 16)}, ${parseInt(h.slice(4, 6), 16)}, ${a})`;
  }

  // ================================================================ barra superior y panel de indicadores
  function actualizarPaneles() {
    const sinSim = !estado || estado.estado === 'SIN_SIMULACION';
    let e = sinSim ? 'SIN_SIMULACION' : estado.estado;
    if (e === 'TERMINADA' && estado.tipoFin === 'COLAPSO') e = 'COLAPSO';   // v04: etiqueta "Colapso" en rojo
    const chip = $('estado');
    chip.className = 'estado ' + e;
    chip.querySelector('span').textContent = ESTADOS_SIM[e] || e;
    chip.title = ESTADOS_SIM[e] || e;                 // en pantallas medianas solo se ve el punto de color

    $('bloqueLinea').hidden = esColapso();           // v04: el colapso no tiene duración definida
    $('btnDetener').disabled = e !== 'EJECUTANDO';
    $('btnDetener').hidden = e === 'PAUSADA';
    $('btnReanudar').hidden = e !== 'PAUSADA';

    const vacio = $('avisoVacio');
    if (sinSim) {
      vacio.hidden = false;
      vacio.innerHTML = esColapso()
        ? '<b>Simulación hasta el colapso</b><span>Elige la fecha y hora de inicio arriba y presiona <em>Iniciar</em>. Continúa hasta que un pedido no se entregue a tiempo.</span>'
        : '<b>Simulación de 5 días</b><span>Elige la fecha y hora de inicio arriba y presiona <em>Iniciar</em>.</span>';
    } else if (estado.ciclos === 0) {
      vacio.hidden = false;
      vacio.innerHTML = '<div class="cargando"></div><b>Preparando la simulación</b><span>Leyendo pedidos y bloqueos y calculando el primer plan de rutas…</span>';
    } else vacio.hidden = true;

    if (sinSim) {
      for (const id of ['gFlota', 'gAlm']) { $(id).textContent = '—'; $(id).className = 't-NEUTRO'; $(id + 'Barra').style.width = '0'; $(id + 'Det').textContent = '—'; }
      return;
    }
    const r = estado.resumen;
    $('kEntregados').textContent = num(r.entregados);
    const kt = $('kATiempo');
    kt.textContent = (r.entregados + r.vencidosSinEntregar) ? r.pctATiempo.toFixed(1) + '%' : '—';
    kt.className = 't-' + r.nivelATiempo;
    kt.title = `${num(r.aTiempo)} a tiempo de ${num(r.entregados + r.vencidosSinEntregar)} pedidos que ya debían llegar`;

    // v0.4.1: el central (abastecimiento permanente) va sin dato; los intermedios, con su % de stock
    const htmlAlm = estado.almacenes.map((a) => a.central
      ? `<li data-id="${a.id}" title="Almacén central: abastecimiento permanente">${CASA}<span>${esc(a.nombre)}</span><span></span></li>`
      : `<li data-id="${a.id}" title="${textoStock(a)}; se recarga a las 23:59:59"><i class="p ${a.nivel}"></i><span>${esc(a.nombre)}</span><span class="pct t-${a.nivel}">${pctStock(a)}%</span></li>`).join('');
    if ($('listaAlmacenes')._html !== htmlAlm) { $('listaAlmacenes').innerHTML = htmlAlm; $('listaAlmacenes')._html = htmlAlm; }

    const total = estado.duracionHoras || 120;
    const f = Math.min(1, estado.horasTranscurridas / total);
    $('ltAvance').style.width = (f * 100) + '%';
    $('ltPunto').style.left = (f * 100) + '%';
    [...$('lineaTiempo').querySelectorAll('.dias span')].forEach((s, i) => s.classList.toggle('hoy', i + 1 === Math.min(5, estado.dia)));

    $('fEnRuta').textContent = num(r.unidadesEnRuta);
    $('fPendientes').textContent = num(r.pendientes);
    $('dVencidos').textContent = num(r.vencidos);
    $('dVencidos').className = r.vencidos ? 't-ROJO' : '';
    $('dFlota').textContent = `${r.unidadesDisponibles} / ${r.flota}`;
    $('dMant').textContent = num(r.unidadesMantenimiento);
    $('dAveriadas').textContent = `${r.averiadasTipo1} · ${r.averiadasTipo2} · ${r.averiadasTipo3}`;   // v03
    $('dAveriadas').className = r.unidadesAveriadas ? 't-ROJO' : '';
    $('nPedidos').textContent = num(estado.pedidos.length);
    $('nUnidades').textContent = num(estado.unidades.filter(pasaUnidad).length);
    // v0.5: indicadores globales con semáforo + vacío (G01 a G04)
    const globales = [['gFlota', r.pctFlota, r.nivelFlota, `${num(r.cargaFlota)} de ${num(r.capacidadFlota)} paquetes a bordo de las unidades en servicio`],
      ['gAlm', r.pctAlmacenes, r.nivelAlmacenes, `${num(r.stockIntermedios)} de ${num(r.capacidadIntermedios)} paquetes en Nor-Oeste y Este`]];
    for (const [id, pct, nivel, det] of globales) {
      const v = Math.floor(Math.max(0, Math.min(100, pct)));
      $(id).textContent = v + '%';
      $(id).className = 't-' + nivel;
      $(id + 'Barra').style.width = v + '%';
      $(id + 'Barra').className = nivel;
      $(id + 'Det').textContent = nivel === 'VACIO' ? 'Vacío · ' + det : det;
    }
    $('nAverias').textContent = num(r.unidadesAveriadas);
    $('tabAverias').classList.toggle('hay', r.unidadesAveriadas > 0);
    $('dKm').textContent = num(r.km) + ' km';
    $('dCosto').textContent = 'S/ ' + num(r.costo);
  }

  function actualizarReloj() {
    if (!estado || estado.estado === 'SIN_SIMULACION') {
      $('reloj').textContent = '--/--/---- --:--:--';
      $('tSim').textContent = '--:--:--'; $('tReal').textContent = '00:00:00';
      $('tHoy').textContent = fmtFecha(new Date());
      return;
    }
    const corre = estado.estado === 'EJECUTANDO';
    const dt = corre ? Math.min(performance.now() - recibidoEn, 1500) / 1000 : 0;
    const base = fechaLocal(estado.hora);
    const sim = base ? new Date(base.getTime() + dt * estado.factor * 1000) : null;
    $('reloj').textContent = fmtFecha(sim);
    const ini = fechaLocal(estado.inicio);          // v0.4.1: tiempo simulado transcurrido (rúbrica C13)
    $('tSim').textContent = sim && ini ? fmtDur(Math.max(0, (sim - ini) / 1000)) : '--:--:--';
    if ($('modalAveria').open) $('avMomento').textContent = `La avería ocurre en el instante actual de la simulación: ${fmtFecha(sim)}.`;
    $('tReal').textContent = fmtDur(estado.segundosReales + dt);
    $('tHoy').textContent = fmtFecha(new Date());
  }

  // ================================================================ panel de rastreo de entregas
  // La lista se actualiza cada medio segundo. Cada pedido conserva su elemento <li> y solo se reescribe
  // si su contenido cambió, para que un clic o el foco del teclado no se pierdan al refrescar.
  const elementosPedido = new Map();
  const liExtra = document.createElement('li');
  /** Ícono de auto, moto o bicicleta para los paneles (mismo dibujo que en el mapa). */
  function iconoUnidad(tipo) {
    if (tipo === 'AUTO') return '<svg class="ico-u" viewBox="-10 -8 20 14"><path d="M-8.5 2.5v-4l3-1 2.2-3.5h7l2.8 3.5 2 1v4z" fill="currentColor" stroke="none"/><circle cx="-4.5" cy="3" r="2.2" fill="var(--superficie-2)" stroke="currentColor" stroke-width="1.5"/><circle cx="4.5" cy="3" r="2.2" fill="var(--superficie-2)" stroke="currentColor" stroke-width="1.5"/></svg>';
    if (tipo === 'MOTO') return '<svg class="ico-u" viewBox="-10 -8 20 14"><circle cx="-5.5" cy="3" r="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><circle cx="5.5" cy="3" r="2.5" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M-6.5 .5l2-2.7 4-.6 2-1.4h2.5L6-.8 4 1.6h-6z" fill="currentColor" stroke="none"/><path d="M4.6-1l.9 4M3-4.2l.8-2.4H6" fill="none" stroke="currentColor" stroke-width="1.5"/></svg>';
    return '<svg class="ico-u" viewBox="-10 -8 20 14"><circle cx="-5" cy="2.5" r="3.2" fill="none" stroke="currentColor" stroke-width="1.2"/><circle cx="5" cy="2.5" r="3.2" fill="none" stroke="currentColor" stroke-width="1.2"/><path d="M-5 2.5l4-5h5l1 5M-1-2.5l1.5 5h-5.5M-2.5-5H0M4-2.5l-.5-3.5" fill="none" stroke="currentColor" stroke-width="1.2"/></svg>';
  }
  function tipoDe(id) { const u = estado && estado.unidades ? estado.unidades.find((x) => x.id === id) : null; return u ? u.tipo : ''; }
  /** Si el buscador tiene exactamente el código de una unidad (p. ej. TA03), la lista muestra solo sus pedidos. */
  function unidadFiltrada() {
    if (!estado || !estado.unidades) return null;
    const q = $('buscar').value.trim().toUpperCase();
    return estado.unidades.some((u) => u.id === q) ? q : null;
  }
  function htmlPedido(p) {
    const consumido = Math.max(0, Math.min(100, Math.round((1 - p.holgura) * 100)));
    // v0.5: un renglón por envío (unidad, cantidad, productos, almacén y hora de salida, hora de llegada), rúbrica E31
    const unidades = p.envios && p.envios.length
      ? `<span class="envios">${p.envios.map((e) => `<span class="envio${sel && sel.tipo === 'pedido' && sel.id === p.id && sel.unidad === e.u ? ' sel' : ''}" data-unidad="${e.u}" title="Ver la ruta de estos productos">`
        + `<button class="u" data-unidad="${e.u}" title="Ver solo ${e.u}" tabindex="-1">${iconoUnidad(tipoDe(e.u))}${e.u}</button>`
        + `<span class="det">${e.q} paq. <code>${rango(p.id, e.p0, e.p1)}</code><br>${textoSalida(e)} → llega ${horaSim(e.ll)}</span></span>`).join('')}</span>`
      : p.unidades.length
        ? p.unidades.map((u) => `<button class="u" data-unidad="${u}" title="Ver solo ${u}" tabindex="-1">${iconoUnidad(tipoDe(u))}${u}</button>`).join('')
        : '<em>sin asignar</em>';
    return `<span class="cod">${p.id}</span><span class="plazo h${p.plazo}">${p.plazo}h</span>
      <span class="cli">${esc(p.cliente)} · (${p.x}, ${p.y}) · ${p.entregado}/${p.cantidad} entregados</span>
      <span class="uni">${unidades}</span>
      <span class="barra-av" title="Porcentaje del plazo ya transcurrido (vence ${fmtCorta(p.limite)})"><span class="riel"><span class="lleno ${p.nivel}" style="width:${consumido}%"></span></span><small>${p.vencido ? 'vencido' : consumido + '%'}${p.nivel === 'ROJO' ? ' ⚠' : ''}</small></span>`;
  }
  function actualizarLista() {
    const ul = $('listaPedidos');
    let lista = estado && estado.pedidos ? estado.pedidos : [];
    const q = $('buscar').value.trim().toLowerCase();
    const uf = unidadFiltrada();
    if (uf) lista = lista.filter((p) => p.unidades.includes(uf));
    else if (q) lista = lista.filter((p) => p.id.toLowerCase().includes(q) || p.cliente.toLowerCase().includes(q) || p.unidades.some((u) => u.toLowerCase().includes(q)));
    lista = [...lista].sort((a, b) => (b.vencido - a.vencido) || (a.holgura - b.holgura));
    $('etiquetaLista').textContent = uf ? `Pedidos de ${uf}` : q ? 'Resultados' : 'En curso';
    $('cuentaPedidos').textContent = `${num(lista.length)} ${lista.length === 1 ? 'pedido' : 'pedidos'}`;
    const MAX = 80;
    const mostrar = lista.slice(0, MAX);
    const vistos = new Set();
    mostrar.forEach((p, i) => {
      let li = elementosPedido.get(p.id);
      if (!li) {
        li = document.createElement('li');
        li.className = 'pedido'; li.dataset.id = p.id; li.tabIndex = 0;
        elementosPedido.set(p.id, li);
      }
      const html = htmlPedido(p);
      if (li._html !== html) { li.innerHTML = html; li._html = html; }
      li.classList.toggle('sel', !!(sel && sel.tipo === 'pedido' && sel.id === p.id));
      if (ul.children[i] !== li) ul.insertBefore(li, ul.children[i] || null);
      vistos.add(p.id);
    });
    for (const [id, li] of elementosPedido) if (!vistos.has(id)) { li.remove(); elementosPedido.delete(id); }
    let extra = '';
    if (!lista.length) extra = estado && estado.pedidos ? (uf ? `${uf} no tiene pedidos asignados en este momento.` : q ? 'Ningún pedido en curso coincide con la búsqueda.' : 'No hay pedidos en curso.') : '';
    else if (lista.length > MAX) extra = `y ${num(lista.length - MAX)} más; usa el buscador para encontrarlos`;
    liExtra.className = lista.length ? 'mas' : 'lista-vacia';
    liExtra.textContent = extra;
    if (extra) ul.appendChild(liExtra); else liExtra.remove();
  }
  function elegirPedido(ev) {
    const chip = ev.target.closest('.u');
    if (chip) { alternarUnidad(chip.dataset.unidad, true); return; }   // clic en la unidad de un pedido
    const li = ev.target.closest('.pedido');
    if (!li) return;
    const envio = ev.target.closest('.envio');                          // v0.5: un envío: solo la ruta de sus productos (F02)
    if (envio) {
      if (sel && sel.tipo === 'pedido' && sel.id === li.dataset.id && sel.unidad === envio.dataset.unidad) seleccionar({ tipo: 'pedido', id: li.dataset.id }, false);
      else seleccionar({ tipo: 'pedido', id: li.dataset.id, unidad: envio.dataset.unidad }, true);
      return;
    }
    if (sel && sel.tipo === 'pedido' && sel.id === li.dataset.id) seleccionar(null);   // segundo clic: quita la selección
    else seleccionar({ tipo: 'pedido', id: li.dataset.id }, true);
  }
  $('listaPedidos').addEventListener('pointerdown', elegirPedido);
  $('listaPedidos').addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); elegirPedido(ev); } });
  $('buscar').addEventListener('input', () => {
    const uf = unidadFiltrada();
    if (uf) { if (!(sel && sel.tipo === 'unidad' && sel.id === uf)) seleccionar({ tipo: 'unidad', id: uf }, true); }
    else if (sel && sel.tipo === 'unidad') seleccionar(null);
    botonLimpiar();
    actualizarListas();
  });
  $('limpiarBuscar').onclick = () => { limpiarFiltro(); $('buscar').focus(); };
  $('listaAlmacenes').addEventListener('pointerdown', (ev) => {
    const li = ev.target.closest('li');
    if (li) { quitarFiltroUnidad(); seleccionar({ tipo: 'almacen', id: li.dataset.id }, true); }
  });
  function botonLimpiar() { $('limpiarBuscar').hidden = !$('buscar').value; }

  // ================================================================ selección, filtro por unidad y detalle
  /** Clic en una unidad: la enfoca y filtra sus pedidos; un segundo clic sobre la misma quita el filtro. */
  function alternarUnidad(id, centrar) {
    if (sel && sel.tipo === 'unidad' && sel.id === id) { limpiarFiltro(); return; }
    $('buscar').value = id;
    botonLimpiar();
    seleccionar({ tipo: 'unidad', id }, centrar);
  }
  /** Quita el filtro del buscador y la selección: el mapa vuelve a mostrar todas las unidades. */
  function limpiarFiltro() {
    $('buscar').value = '';
    botonLimpiar();
    seleccionar(null);
  }
  function quitarFiltroUnidad() {
    if (unidadFiltrada()) { $('buscar').value = ''; botonLimpiar(); }
  }
  function seleccionar(s, centrar) {
    sel = s;
    if (s && centrar) centrarEn(s);
    mostrarDetalle();
    actualizarListas();
    sucio = true;
  }
  /**
   * v0.5: clic en una unidad o un almacén del mapa: se elige, se abre su pestaña del panel derecho y su fila queda
   * resaltada y visible (vuelta del enlace bidireccional mapa-panel, rúbrica F01 y F05). Un segundo clic la suelta.
   */
  function desdeMapa(tipo, id) {
    if (sel && sel.tipo === tipo && sel.id === id) { limpiarFiltro(); return; }
    quitarFiltroUnidad();
    seleccionar({ tipo, id });
    abrirPanel('panelDer');                      // los paneles empiezan contraídos (E01): se abre para mostrar la fila
    elegirPestana(tipo === 'unidad' ? 'unidades' : 'almacenes');
    const fila = document.querySelector(tipo === 'unidad' ? `#listaUnidades li[data-id="${id}"]` : `#listaAlmacenesDer li[data-id="${id}"]`);
    if (fila) fila.scrollIntoView({ block: 'nearest' });
  }
  function centrarEn(s) {
    const p = ubicacion(s);
    if (!p) return;
    // v0.5: se desplaza y acerca de forma continua hasta dejar el punto un poco arriba del centro (abajo va el detalle)
    const z = Math.max(zoomAnim.activo ? zoomAnim.z : vista.zoom, 2.5);
    const w = cv.clientWidth, h = cv.clientHeight;
    const sc = Math.min((w - 2 * MARGEN) / ANCHO, (h - 2 * MARGEN) / ALTO) * z;
    irA(z, w / 2 - (w - ANCHO * sc) / 2 - p[0] * sc, h * 0.36 - (h - ALTO * sc) / 2 - (ALTO - p[1]) * sc);
  }
  function ubicacion(s) {
    if (!estado) return null;
    if (s.tipo === 'pedido') { const p = estado.pedidos.find((x) => x.id === s.id); return p && [p.x, p.y]; }
    if (s.tipo === 'almacen') { const a = estado.almacenes.find((x) => x.id === s.id); return a && [a.x, a.y]; }
    if (s.tipo === 'unidad') { const u = estado.unidades.find((x) => x.id === s.id); return u && [u.x, u.y]; }
    return null;
  }

  function mostrarDetalle() {
    const caja = $('detalle'), cuerpo = $('detalleCuerpo'), acciones = $('detalleAcciones');
    acciones.hidden = true;
    if (!sel || !estado || !estado.unidades) { caja.hidden = true; return; }
    caja.hidden = false;
    let html = '';
    if (sel.tipo === 'unidad') {
      const u = estado.unidades.find((x) => x.id === sel.id);
      if (!u) { caja.hidden = true; return; }
      const entregas = u.paradas.filter((p) => p.tipo === 'ENTREGA');
      const pedidosU = [...new Set(entregas.map((p) => p.ref))];
      const av = averiaDe(u);
      let n = 0;
      const chip = u.averia ? `<span class="chip AVERIADA">Averiada · tipo ${u.averia.tipo}</span>`
        : `<span class="chip ${u.estado}">${ESTADOS_U[u.estado] || u.estado}</span>`;
      const paradas = u.paradas.length ? `${av ? '<p class="nota">Plan para cuando termine la avería:</p>' : ''}<ul class="paradas">${u.paradas.slice(0, 8).map((p) => {
        const trv = p.tipo === 'RECARGA' && esTrasvase(p.ref);
        return `<li><b>${p.tipo === 'ENTREGA' ? ++n : trv ? 'T' : p.tipo === 'RECARGA' ? 'R' : 'C'}</b><span>${
          p.tipo === 'ENTREGA' ? `Entregar ${p.cantidad} a ${p.ref} en (${p.x}, ${p.y}) · <code>${rango(p.ref, p.p0, p.p1)}</code>`
            : trv ? `Recoger ${p.cantidad} paquetes de ${p.ref.slice(4)} (trasvase)`
              : p.tipo === 'RECARGA' ? `Recargar en ${nombreAlmacen(p.ref)}` : 'Refrigerio (1 h)'} · llega ${horaSim(p.ll)}</span></li>`;
      }).join('')}${u.paradas.length > 8 ? `<li><b></b>y ${u.paradas.length - 8} paradas más</li>` : ''}</ul>` : '';
      const nota = u.paradas.length || av ? '' : `<p class="nota">${u.estado === 'MANTENIMIENTO'
        ? `Fuera de servicio por mantenimiento preventivo hasta el ${fmtCorta(u.noDisponibleHasta)}.` : 'Sin paradas asignadas.'}</p>`;
      html = `<h4>${u.id} ${chip}</h4>
        <div class="sub">${TIPOS[u.tipo]} · ${u.km} km recorridos</div>
        <dl><dt>Carga</dt><dd class="t-${u.nivel}">${u.carga} / ${u.capacidad} paquetes${u.nivel === 'VACIO' ? ' (vacía)' : ''}</dd>
        <dt>Origen</dt><dd>${esc(nombreAlmacen(u.origen))}${u.salida >= 0 ? ' · salió ' + horaSim(u.salida) : ''}</dd>
        <dt>Posición</dt><dd>(${u.x.toFixed(1)}, ${u.y.toFixed(1)})</dd>
        <dt>Pedidos asignados</dt><dd>${pedidosU.length ? pedidosU.join(', ') : '—'}</dd>${av ? filasAveria(av) : ''}</dl>
        ${av ? `<p class="estado-av">${textoFaseAveria(av)}</p>` : ''}${paradas}${nota}`;
      // v03: desde el detalle se registra la avería de una unidad operativa (DS GUI, sección 3.4)
      if (puedeAveriarse(u)) { acciones.hidden = false; $('btnAveriarUnidad').dataset.unidad = u.id; }
    } else if (sel.tipo === 'almacen') {
      const a = estado.almacenes.find((x) => x.id === sel.id);
      const aqui = averiadasEn(a);
      html = `<h4>${esc(a.nombre)}</h4>
        <div class="sub">${a.central ? 'Almacén central, permanentemente abastecido' : `Almacén intermedio, se recarga a ${a.capacidad} paquetes a las 23:59:59`}</div>
        <dl><dt>Ubicación</dt><dd>(${a.x}, ${a.y})</dd>
        ${a.central ? '' : `<dt>Stock</dt><dd class="t-${a.nivel}">${pctStock(a)}% · ${a.stock} de ${a.capacidad} paquetes</dd>`}
        <dt>Vienen a recargar</dt><dd>${a.recargan.length ? a.recargan.join(', ') : '—'}</dd>
        ${aqui.length ? `<dt>Averiadas aquí</dt><dd class="t-ROJO">${aqui.map((u) => `${u.id} (tipo ${u.averia.tipo})`).join(', ')}</dd>` : ''}</dl>`;
    } else if (sel.tipo === 'pedido') {
      const p = estado.pedidos.find((x) => x.id === sel.id);
      if (!p) html = `<h4>${esc(sel.id)} <span class="chip DISPONIBLE">Entregado</span></h4><p class="nota">El pedido ya se entregó por completo y salió de la lista de pedidos en curso.</p>`;
      else html = `<h4>${p.id} ${p.vencido ? '<span class="chip rojo">Vencido</span>' : ''}</h4>
        <div class="sub">Cliente ${esc(p.cliente)} · nodo (${p.x}, ${p.y})</div>
        <dl><dt>Cantidad</dt><dd>${p.cantidad} (entregado ${p.entregado}, en camino ${p.asignado})</dd>
        <dt>Plazo</dt><dd>${p.plazo} h</dd>
        <dt>Registrado</dt><dd>${fmtCorta(p.registro)}</dd>
        <dt>Límite</dt><dd class="t-${p.nivel}">${fmtCorta(p.limite)}</dd>
        <dt>Atendido por</dt><dd>${p.unidades.length ? p.unidades.join(', ') : 'sin asignar'}</dd>
        ${p.entregado ? `<dt>Productos entregados</dt><dd><code>${rango(p.id, 1, p.entregado)}</code></dd>` : ''}</dl>
        ${tablaEnvios(p)}`;
    } else if (sel.tipo === 'bloqueo') {
      const b = estado.bloqueos[sel.id];
      if (!b) { caja.hidden = true; sel = null; return; }
      html = `<h4>Calle bloqueada</h4><div class="sub">No se puede atravesar ni girar en sus nodos</div>
        <dl><dt>Desde</dt><dd>${fmtCorta(b.inicio)}</dd><dt>Hasta</dt><dd>${fmtCorta(b.fin)}</dd>
        <dt>Tramos</dt><dd>${b.vertices.map((v) => `(${v[0]}, ${v[1]})`).join(' → ')}</dd></dl>`;
    }
    if (cuerpo._html !== html) { cuerpo.innerHTML = html; cuerpo._html = html; }   // sin parpadeo si no cambió
  }

  // ================================================================ v03: averías
  /** Avería vigente de una unidad, con todo su detalle (de la lista estado.averias). */
  function averiaDe(u) {
    if (!u || !u.averia || !estado || !estado.averias) return null;
    return estado.averias.find((a) => a.id === u.averia.id) || null;
  }
  /** Unidades que vienen a trasvasar los paquetes de una averiada o que lo están haciendo ahora. */
  function ayudantes(av) {
    if (!av) return [];
    const ahora = av.receptoras.filter((r) => estado.unidades.some((u) => u.id === r && u.estado === 'TRASVASE'));
    return [...new Set([...av.vanATrasvasar, ...ahora])];
  }
  function puedeAveriarse(u) {
    return !!estado && (estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA') && !u.averia && u.estado !== 'MANTENIMIENTO';
  }
  function averiadasEn(a) {
    return estado.unidades.filter((u) => u.averia && u.averia.fase === 'EN_CENTRAL' && Math.abs(u.x - a.x) < 0.01 && Math.abs(u.y - a.y) < 0.01);
  }
  const mayuscula = (t) => t.charAt(0).toUpperCase() + t.slice(1);
  const hhmm = (iso) => { const d = fechaLocal(iso); return d ? `${dos(d.getHours())}:${dos(d.getMinutes())}` : '--'; };
  /** Tiempo simulado que falta para un instante: "faltan 2 h 15 min". */
  function faltan(iso) {
    const d = fechaLocal(iso), ahora = fechaLocal(estado.hora);
    if (!d || !ahora) return '';
    const min = Math.max(0, Math.round((d - ahora) / 60000));
    if (min === 0) return 'ya';
    const dias = Math.floor(min / 1440), h = Math.floor(min % 1440 / 60), m = min % 60;
    return 'faltan ' + (dias ? `${dias} d ` : '') + (h ? `${h} h ` : '') + (dias ? '' : `${m} min`);
  }
  function duracion(desde, hasta) {
    const a = fechaLocal(desde), b = fechaLocal(hasta);
    if (!a || !b) return '';
    const min = Math.max(0, Math.round((b - a) / 60000));
    return `${Math.floor(min / 60)} h ${dos(min % 60)} min`;
  }
  function filasAveria(av) {
    let f = `<dt>Avería</dt><dd class="t-ROJO">Tipo ${av.tipo} (${NOMBRE_TIPO_AV[av.tipo]})</dd>
      <dt>Ocurrió</dt><dd>${fmtCorta(av.inicio)} · registrada</dd>
      <dt>Lugar</dt><dd>(${av.x.toFixed(1)}, ${av.y.toFixed(1)})</dd>`;
    if (av.tipo > 1) f += `<dt>En el lugar hasta</dt><dd>${fmtCorta(av.finLugar)}</dd>`;
    f += `<dt>Disponible desde</dt><dd>${fmtCorta(av.disponible)}</dd>`;
    if (av.tipo > 1) {
      f += `<dt>Trasvasados</dt><dd>${av.trasvasados}${av.receptoras.length ? ' a ' + av.receptoras.join(', ') : ''}</dd>`;
      if (av.vanATrasvasar.length) f += `<dt>Vienen a trasvasar</dt><dd>${av.vanATrasvasar.join(', ')}</dd>`;
      if (av.fase !== 'EN_LUGAR') f += `<dt>Devueltos al central</dt><dd>${av.devueltos}</dd>`;
    }
    return f + `<dt>Pedidos replanificados</dt><dd>${av.pedidos.length ? av.pedidos.join(', ') : 'ninguno'}</dd>`;
  }
  function textoFaseAveria(av) {
    if (av.fase === 'EN_CENTRAL') return `En el almacén central, fuera de servicio hasta el ${fmtCorta(av.disponible)} (${faltan(av.disponible)}).`;
    if (av.tipo === 1) return `Detenida en su posición con su carga; vuelve a operar a las ${hhmm(av.disponible)} (${faltan(av.disponible)}).`;
    const trasvase = av.trasvasable && av.cargaActual > 0
      ? `Sus ${av.cargaActual} paquetes se pueden trasvasar hasta las ${hhmm(av.finLugar)}; luego` : 'A las ' + hhmm(av.finLugar);
    return `Detenida en su posición. ${trasvase} pasa de forma instantánea al almacén central (${faltan(av.finLugar)}).`;
  }

  // ---- lista de unidades averiadas por tipo e historial (pestaña Averías del panel derecho)
  const elementosAveria = new Map();
  let pestana = 'pedidos';
  // v0.5: cuatro pestañas en el panel derecho: Pedidos, Unidades, Almacenes y Averías
  const PESTANAS = {
    pedidos: ['tabPedidos', 'vistaPedidos', 'Rastreo de entregas', 'Buscar pedido, cliente o unidad (TA03)'],
    unidades: ['tabUnidades', 'vistaUnidades', 'Unidades de transporte', 'Buscar unidad (TA03) o pedido'],
    almacenes: ['tabAlmacenes', 'vistaAlmacenes', 'Almacenes', 'Buscar pedido, cliente o unidad'],
    averias: ['tabAverias', 'vistaAverias', 'Unidades averiadas', 'Buscar unidad o pedido'],
  };
  function elegirPestana(p) {
    pestana = p;
    for (const [nombre, [id, vista]] of Object.entries(PESTANAS)) {
      $(id).classList.toggle('activa', p === nombre);
      $(id).setAttribute('aria-selected', String(p === nombre));
      $(vista).hidden = p !== nombre;
    }
    $('tituloDer').textContent = PESTANAS[p][2];
    $('buscar').placeholder = PESTANAS[p][3];
    actualizarListas();
  }
  for (const nombre of Object.keys(PESTANAS)) $(PESTANAS[nombre][0]).onclick = () => elegirPestana(nombre);
  /** v0.5: actualiza las listas del panel derecho (solo se dibuja la de la pestaña visible). */
  function actualizarListas() {
    actualizarLista();
    actualizarListaUnidades();
    actualizarListaAlmacenesDer();
    actualizarListaAverias();
  }

  function htmlAveria(a) {
    const activa = a.fase !== 'FINALIZADA';
    let info;
    if (!activa) info = `${fmtCorta(a.inicio)} → ${fmtCorta(a.fin)} (${duracion(a.inicio, a.fin)}) · ${a.pedidos.length} ${a.pedidos.length === 1 ? 'pedido replanificado' : 'pedidos replanificados'}`;
    else if (a.fase === 'EN_CENTRAL') info = `Fuera de servicio en el almacén central · ${a.devueltos} paquetes devueltos`;
    else if (a.tipo === 1) info = `Detenida en (${a.x.toFixed(1)}, ${a.y.toFixed(1)}) con ${a.cargaActual} paquetes`;
    else if (a.trasvasable && a.cargaActual > 0) info = `Detenida en (${a.x.toFixed(1)}, ${a.y.toFixed(1)}) · ${a.cargaActual} paquetes por trasvasar hasta las ${hhmm(a.finLugar)}`;
    else info = `Detenida en (${a.x.toFixed(1)}, ${a.y.toFixed(1)}) con ${a.cargaActual} paquetes · al almacén central a las ${hhmm(a.finLugar)}`;
    let extra = activa ? `Ocurrió ${fmtCorta(a.inicio)} · disponible ${fmtCorta(a.disponible)}`
      : a.tipo > 1 ? `Trasvasados ${a.trasvasados} · devueltos al central ${a.devueltos}` : '';
    if (activa && a.vanATrasvasar.length) extra += ` · vienen ${a.vanATrasvasar.join(', ')}`;
    let barra = '';
    if (activa) {
      const i = fechaLocal(a.inicio), d = fechaLocal(a.disponible), ahora = fechaLocal(estado.hora);
      const f = d > i ? Math.max(0, Math.min(1, (ahora - i) / (d - i))) : 1;
      barra = `<span class="barra-av" title="Tiempo transcurrido de la avería"><span class="riel"><span class="lleno AV" style="width:${Math.round(f * 100)}%"></span></span><small>${faltan(a.disponible)}</small></span>`;
    }
    return `<span class="cod">${iconoUnidad(a.tipoUnidad)}${a.unidad}<span class="tipo-av t${a.tipo}">Tipo ${a.tipo}</span></span>
      <span class="fase ${a.fase}">${FASES_AV[a.fase]}</span>
      <span class="info">${info}</span>${extra ? `<span class="info">${extra}</span>` : ''}${barra}`;
  }
  function actualizarListaAverias() {
    const todas = estado && estado.averias ? estado.averias : [];
    const activasTodas = todas.filter((a) => a.fase !== 'FINALIZADA');
    const reglas = estado && estado.reglasAveria;
    const htmlRes = [1, 2, 3].map((t) => {
      const n = activasTodas.filter((a) => a.tipo === t).length;
      return `<div class="${n ? 'con' : ''}"><b>${n}</b><span>Tipo ${t} · ${NOMBRE_TIPO_AV[t]}</span></div>`;
    }).join('');
    if ($('resumenAverias')._html !== htmlRes) { $('resumenAverias').innerHTML = htmlRes; $('resumenAverias')._html = htmlRes; }
    $('btnNuevaAveria').disabled = !(estado && (estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA'));
    if (pestana !== 'averias') return;

    const q = $('buscar').value.trim().toLowerCase();
    const coincide = (a) => !q || a.unidad.toLowerCase().includes(q) || a.id.toLowerCase().includes(q) || a.pedidos.some((p) => p.toLowerCase() === q);
    const activas = activasTodas.filter(coincide);
    const terminadas = todas.filter((a) => a.fase === 'FINALIZADA' && coincide(a));
    const filas = [];
    const reglaTipo = {
      1: `${cfgAveria.tipo1Horas} h detenida, sigue con su carga`,
      2: `${cfgAveria.enLugarHoras} h en el lugar, vuelve al final del turno siguiente`,
      3: `${cfgAveria.enLugarHoras} h en el lugar, vuelve tras ${cfgAveria.tipo3Dias} días a las ${cfgAveria.tipo3Hora}`,
    };
    for (const t of [1, 2, 3]) {
      const del = activas.filter((a) => a.tipo === t);
      filas.push({ clave: 'g' + t, clase: 'grupo-av', html: `<span>Tipo ${t} · ${NOMBRE_TIPO_AV[t]} <small>${reglaTipo[t]}</small></span><b>${del.length}</b>` });
      del.forEach((a) => filas.push({ clave: a.id, clase: 'averia t' + a.tipo, unidad: a.unidad, html: htmlAveria(a) }));
      if (!del.length) filas.push({ clave: 'v' + t, clase: 'vacio-av', html: q ? 'Ninguna coincide con la búsqueda.' : 'Ninguna unidad con este tipo de avería.' });
    }
    if (terminadas.length) {
      filas.push({ clave: 'gh', clase: 'grupo-av', html: `<span>Historial <small>averías terminadas</small></span><b>${terminadas.length}</b>` });
      terminadas.forEach((a) => filas.push({ clave: a.id, clase: 'averia fin t' + a.tipo, unidad: a.unidad, html: htmlAveria(a) }));
    }
    if (!todas.length) filas.push({ clave: 'nada', clase: 'lista-vacia', html: estado && estado.unidades
      ? 'Sin averías por ahora. Regístralas con el botón Registrar avería.' : 'Inicia una simulación para registrar averías.' });

    const ul = $('listaAverias');
    const vistos = new Set();
    filas.forEach((f, i) => {
      let li = elementosAveria.get(f.clave);
      if (!li) { li = document.createElement('li'); elementosAveria.set(f.clave, li); if (f.unidad) li.tabIndex = 0; }
      if (li.className !== f.clase) li.className = f.clase;
      if (f.unidad) li.dataset.unidad = f.unidad;
      if (li._html !== f.html) { li.innerHTML = f.html; li._html = f.html; }
      li.classList.toggle('sel', !!(f.unidad && f.clase.indexOf('fin') < 0 && sel && sel.tipo === 'unidad' && sel.id === f.unidad));
      if (ul.children[i] !== li) ul.insertBefore(li, ul.children[i] || null);
      vistos.add(f.clave);
    });
    for (const [clave, li] of elementosAveria) if (!vistos.has(clave)) { li.remove(); elementosAveria.delete(clave); }
  }
  /** Del panel al mapa: al elegir una unidad averiada, el mapa se centra en ella y la resalta. */
  function elegirAveria(ev) {
    const li = ev.target.closest('.averia');
    if (!li) return;
    const id = li.dataset.unidad;
    if (sel && sel.tipo === 'unidad' && sel.id === id) seleccionar(null);
    else { quitarFiltroUnidad(); seleccionar({ tipo: 'unidad', id }, true); }
  }
  $('listaAverias').addEventListener('pointerdown', elegirAveria);
  $('listaAverias').addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); elegirAveria(ev); } });

  // ---- diálogo "Registrar avería" (la confirmación se hace en el mismo diálogo)
  let cfgAveria = { tipo1Horas: 2, enLugarHoras: 4, tipo3Dias: 2, tipo3Hora: '15:00', trasvaseMin: 30 };
  const propias = new Set();       // averías registradas desde este navegador (su aviso ya se mostró)
  function abrirRegistroAveria(preferida) {
    if (!estado || !(estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA')) { avisar('Inicia una simulación para registrar averías.', 'alerta'); return; }
    const orden = { EN_RUTA: 0, ENTREGANDO: 1, TRASVASE: 2, REFRIGERIO: 3, DISPONIBLE: 4 };
    const opciones = estado.unidades.filter((u) => !u.averia && u.estado !== 'MANTENIMIENTO')
      .sort((a, b) => ((orden[a.estado] ?? 9) - (orden[b.estado] ?? 9)) || a.id.localeCompare(b.id));
    if (!opciones.length) { avisar('No hay unidades operativas para registrar una avería.', 'alerta'); return; }
    $('avUnidad').innerHTML = opciones.map((u) => `<option value="${u.id}">${u.id} · ${TIPOS[u.tipo]} · ${ESTADOS_U[u.estado] || u.estado} · ${u.carga}/${u.capacidad} paquetes</option>`).join('');
    $('avUnidad').value = preferida && opciones.some((u) => u.id === preferida) ? preferida : opciones[0].id;
    $('avDesc1').textContent = `Queda detenida ${cfgAveria.tipo1Horas} h en su posición con su carga y luego sigue operando.`;
    $('avDesc2').textContent = `Permanece ${cfgAveria.enLugarHoras} h en el lugar (sus paquetes se pueden trasvasar a otra unidad en ${cfgAveria.trasvaseMin} min) y luego pasa al almacén central. Vuelve al final del turno siguiente.`;
    $('avDesc3').textContent = `Permanece ${cfgAveria.enLugarHoras} h en el lugar y luego pasa al almacén central. Vuelve tras al menos ${cfgAveria.tipo3Dias} días, en el turno de ${cfgAveria.tipo3Hora}.`;
    $('avError').hidden = true;
    actualizarDialogoAveria();
    $('modalAveria').showModal();
  }
  function actualizarDialogoAveria() {
    if (!$('modalAveria').open || !estado || !estado.reglasAveria) return;
    const r = estado.reglasAveria;
    $('avEst1').textContent = `Disponible: ${fmtCorta(r.disponibleTipo1)}`;
    $('avEst2').textContent = `Al almacén central: ${fmtCorta(r.trasladoTipo23)} · disponible: ${fmtCorta(r.disponibleTipo2)}`;
    $('avEst3').textContent = `Al almacén central: ${fmtCorta(r.trasladoTipo23)} · disponible: ${fmtCorta(r.disponibleTipo3)}`;
    const u = estado.unidades.find((x) => x.id === $('avUnidad').value);
    if (u) {
      const ped = [...new Set(u.paradas.filter((p) => p.tipo === 'ENTREGA').map((p) => p.ref))];
      $('avEfecto').textContent = `${u.id} queda detenida en (${u.x.toFixed(1)}, ${u.y.toFixed(1)}) con ${u.carga} paquetes. `
        + (ped.length ? `${mayuscula(pedidosReplanificados(ped.length))} de inmediato (${ped.slice(0, 6).join(', ')}${ped.length > 6 ? '…' : ''}).` : 'No tiene pedidos asignados en este momento.')
        + ' La calle no se bloquea.';
    }
  }
  $('avUnidad').addEventListener('change', actualizarDialogoAveria);
  $('avCancelar').onclick = () => $('modalAveria').close();
  $('avConfirmar').onclick = async () => {
    const unidad = $('avUnidad').value;
    const tipo = document.querySelector('input[name="avTipo"]:checked').value;
    if (!unidad) return;
    $('avConfirmar').disabled = true;
    try {
      const r = await post(`/api/averias?unidad=${encodeURIComponent(unidad)}&tipo=${tipo}`);
      const av = r.averia;
      propias.add(av.id);
      $('modalAveria').close();
      avisar(`Avería tipo ${av.tipo} registrada en ${av.unidad}: queda detenida en (${av.x.toFixed(1)}, ${av.y.toFixed(1)})`
        + (av.pedidos.length ? ` y ${pedidosReplanificados(av.pedidos.length)}` : '') + `. Disponible el ${fmtCorta(av.disponible)}.`, 'error');
      quitarFiltroUnidad();
      seleccionar({ tipo: 'unidad', id: av.unidad }, true);
    } catch (e) {
      $('avError').textContent = 'No se pudo registrar: ' + e.message;
      $('avError').hidden = false;
    } finally {
      $('avConfirmar').disabled = false;
    }
  };
  $('btnNuevaAveria').onclick = () => abrirRegistroAveria(sel && sel.tipo === 'unidad' ? sel.id : null);
  $('btnAveriarUnidad').onclick = (ev) => abrirRegistroAveria(ev.currentTarget.dataset.unidad);

  /** Avisos de las averías que ocurren (registradas en otro dispositivo), de los traslados, trasvases y retornos. */
  function avisosAverias(prev) {
    if (!prev || !prev.averias || !estado.averias || prev.inicio !== estado.inicio || prev.escenario !== estado.escenario) return;
    const antes = new Map(prev.averias.map((a) => [a.id, a]));
    for (const a of [...estado.averias].reverse()) {
      const p = antes.get(a.id);
      if (!p) {
        if (!propias.has(a.id)) {
          avisar(`Avería tipo ${a.tipo} en ${a.unidad}: `
            + (a.pedidos.length ? `${pedidosReplanificados(a.pedidos.length)}.` : 'no tenía pedidos asignados.'), 'error');
        }
        continue;
      }
      for (const r of a.receptoras) if (!p.receptoras.includes(r)) avisar(`Trasvase: ${r} recoge paquetes de ${a.unidad}.`);
      if (p.fase === 'EN_LUGAR' && a.fase === 'EN_CENTRAL') avisar(`${a.unidad} pasó al almacén central con ${a.devueltos} ${a.devueltos === 1 ? 'paquete no trasvasado' : 'paquetes no trasvasados'}.`, 'alerta');
      if (p.fase !== 'FINALIZADA' && a.fase === 'FINALIZADA') avisar(`${a.unidad} vuelve a operar (avería tipo ${a.tipo}).`);
    }
  }

  function nombreAlmacen(id) {
    if (esTrasvase(id)) return 'trasvase de ' + id.slice(4);
    const a = estado.almacenes.find((x) => x.id === id); return a ? a.nombre : id;
  }
  $('cerrarDetalle').onclick = () => { if (sel && sel.tipo === 'unidad') limpiarFiltro(); else seleccionar(null); };

  // ================================================================ mouse: arrastrar, acercar, señalar
  function objetoEn(px, py) {
    if (!estado || !estado.unidades) return null;
    const g = geom();
    for (const d of dibujadas) if (Math.hypot(d.px - px, d.py - py) < 13) return { tipo: 'unidad', id: d.unidades[0].id, grupo: d.unidades };
    for (const a of estado.almacenes) { const [x, y] = aPantalla(a.x, a.y, g); if (Math.hypot(x - px, y - py) < 20) return { tipo: 'almacen', id: a.id }; }
    for (const p of estado.pedidos) { const [x, y] = aPantalla(p.x, p.y, g); if (Math.hypot(x - px, y - py) < 7) return { tipo: 'pedido', id: p.id }; }
    for (let i = 0; i < estado.bloqueos.length; i++) {
      const v = estado.bloqueos[i].vertices;
      for (let k = 0; k + 1 < v.length; k++) {
        const [ax, ay] = aPantalla(v[k][0], v[k][1], g), [bx, by] = aPantalla(v[k + 1][0], v[k + 1][1], g);
        if (distSeg(px, py, ax, ay, bx, by) < 6) return { tipo: 'bloqueo', id: i };
      }
    }
    return null;
  }
  function distSeg(px, py, ax, ay, bx, by) {
    const dx = bx - ax, dy = by - ay, l = dx * dx + dy * dy;
    const t = l ? Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / l)) : 0;
    return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
  }

  function textoTooltip(o) {
    if (o.tipo === 'unidad') {
      if (o.grupo.length > 1) return `<b>${o.grupo.length} unidades aquí</b><br><span>${o.grupo.map((u) => u.averia ? `${u.id} (avería tipo ${u.averia.tipo})` : u.id).join(', ')}</span>`;
      const u = o.grupo[0];
      if (u.averia) {                    // v03: tipo de avería, hora de ocurrencia e indisponibilidad (DS GUI, tabla 3)
        const a = u.averia;
        const lugar = a.fase === 'EN_CENTRAL' ? 'en el almacén central' : a.tipo === 1 ? 'detenida en su posición' : `en el lugar hasta las ${hhmm(a.finLugar)}`;
        return `<b>${u.id}</b> · ${TIPOS[u.tipo]}<br><span class="t-ROJO">Averiada · tipo ${a.tipo} (${NOMBRE_TIPO_AV[a.tipo]})</span>`
          + `<br><span>Ocurrió ${fmtCorta(a.inicio)} · ${lugar}</span>`
          + `<br><span>No disponible hasta ${fmtCorta(a.disponible)} (${faltan(a.disponible)})</span>`
          + (u.carga ? `<br><span>${u.carga} paquetes a bordo</span>` : '');
      }
      const prox = u.paradas[0];
      return `<b>${u.id}</b> · ${TIPOS[u.tipo]}<br><span>Carga ${u.carga}/${u.capacidad} (${Math.round(100 * u.carga / u.capacidad)}%) · ${ESTADOS_U[u.estado] || u.estado}</span>`
        + (u.noDisponibleHasta ? `<br><span>No disponible hasta ${fmtCorta(u.noDisponibleHasta)}</span>` : '')
        + (prox ? `<br><span>Próxima parada: ${prox.tipo === 'ENTREGA' ? 'entrega ' + prox.ref : prox.tipo === 'RECARGA' ? (esTrasvase(prox.ref) ? 'trasvase de ' + prox.ref.slice(4) : 'recarga en ' + nombreAlmacen(prox.ref)) : 'refrigerio'}</span>` : '');
    }
    if (o.tipo === 'almacen') {
      const a = estado.almacenes.find((x) => x.id === o.id);
      const aqui = averiadasEn(a);
      return `<b>${esc(a.nombre)}</b><br><span>${a.central ? 'Abastecimiento permanente' : `Stock ${pctStock(a)}% · ${textoStock(a)}`}</span>`
        + `<br><span>Vienen a recargar: ${a.recargan.length ? a.recargan.slice(0, 8).join(', ') + (a.recargan.length > 8 ? '…' : '') : 'ninguna'}</span>`
        + (aqui.length ? `<br><span class="t-ROJO">Averiadas aquí: ${aqui.map((u) => `${u.id} (tipo ${u.averia.tipo})`).join(', ')}</span>` : '');
    }
    if (o.tipo === 'pedido') {
      const p = estado.pedidos.find((x) => x.id === o.id);
      return `<b>${p.id}</b> · cliente ${esc(p.cliente)}<br><span>${p.cantidad} paquetes (entregados ${p.entregado}) · plazo ${p.plazo} h</span><br><span>Límite ${fmtCorta(p.limite)}${p.vencido ? ' · vencido' : ''}</span>`;
    }
    const b = estado.bloqueos[o.id];
    return `<b>Calle bloqueada</b><br><span>${fmtCorta(b.inicio)} a ${fmtCorta(b.fin)}</span><br><span>${b.vertices.map((v) => `(${v[0]},${v[1]})`).join(' → ')}</span>`;
  }

  let arrastre = null;
  cv.addEventListener('pointerdown', (e) => {
    zoomAnim.activo = false;                   // v0.5: arrastrar detiene el zoom animado
    arrastre = { x: e.clientX, y: e.clientY, ox: vista.ox, oy: vista.oy, movio: false };
    cv.setPointerCapture(e.pointerId);
  });
  cv.addEventListener('pointermove', (e) => {
    const r = cv.getBoundingClientRect();
    const px = e.clientX - r.left, py = e.clientY - r.top;
    if (arrastre) {
      const dx = e.clientX - arrastre.x, dy = e.clientY - arrastre.y;
      if (Math.abs(dx) + Math.abs(dy) > 3) { arrastre.movio = true; cv.classList.add('arrastrando'); $('tooltip').hidden = true; }
      if (arrastre.movio) { vista.ox = arrastre.ox + dx; vista.oy = arrastre.oy + dy; sucio = true; }
      return;
    }
    const o = objetoEn(px, py);
    const tt = $('tooltip');
    if (!o) { tt.hidden = true; cv.style.cursor = ''; return; }
    cv.style.cursor = 'pointer';
    tt.innerHTML = textoTooltip(o);
    tt.hidden = false;
    const ancho = tt.offsetWidth, alto = tt.offsetHeight;
    tt.style.left = Math.min(px + 14, r.width - ancho - 8) + 'px';
    tt.style.top = Math.min(py + 14, r.height - alto - 8) + 'px';
  });
  cv.addEventListener('pointerup', (e) => {
    const r = cv.getBoundingClientRect();
    if (arrastre && !arrastre.movio) {
      const o = objetoEn(e.clientX - r.left, e.clientY - r.top);
      if (!o) { if (!enfoque()) seleccionar(null); }          // en enfoque, un clic en el vacío no lo quita
      else if (o.tipo === 'unidad') desdeMapa('unidad', o.id);          // v0.5: del mapa a la lista de unidades (F01)
      else if (o.tipo === 'almacen') desdeMapa('almacen', o.id);        // v0.5: del mapa a la lista de almacenes (F05)
      else if (o.tipo === 'pedido' && sel && sel.tipo === 'pedido' && sel.id === o.id) seleccionar(null);
      else { if (o.tipo !== 'pedido') quitarFiltroUnidad(); seleccionar({ tipo: o.tipo, id: o.id }); }
    }
    arrastre = null;
    cv.classList.remove('arrastrando');
  });
  cv.addEventListener('pointerleave', () => { $('tooltip').hidden = true; });
  // v0.5: zoom continuo con la rueda (rúbrica C08): cada giro fija un objetivo y el mapa se acerca de a pocos,
  // manteniendo fijo el punto bajo el mouse. Con un touchpad, el desplazamiento fino da un zoom fino.
  cv.addEventListener('wheel', (e) => {
    e.preventDefault();
    const r = cv.getBoundingClientRect();
    const d = e.deltaMode === 1 ? e.deltaY * 16 : e.deltaMode === 2 ? e.deltaY * 400 : e.deltaY;
    acercar(Math.exp(-Math.max(-300, Math.min(300, d)) * 0.0018), e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });
  cv.addEventListener('dblclick', () => irA(1, 0, 0));
  /** Fija un nuevo objetivo de zoom alrededor del punto (px, py) de la pantalla; el bucle de dibujo lo alcanza. */
  function acercar(f, px, py) {
    const mismoPunto = zoomAnim.activo && zoomAnim.modo === 'ancla' && Math.abs(zoomAnim.px - px) < 2 && Math.abs(zoomAnim.py - py) < 2;
    const base = zoomAnim.activo && zoomAnim.modo === 'ancla' ? zoomAnim.z : vista.zoom;
    const [mx, my] = mismoPunto ? [zoomAnim.mx, zoomAnim.my] : aMundo(px, py, geom());
    Object.assign(zoomAnim, { activo: true, modo: 'ancla', z: Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, base * f)), px, py, mx, my });
    sucio = true;
  }
  /** Desplaza y acerca de forma continua hasta un zoom y un desplazamiento dados. */
  function irA(z, ox, oy) {
    Object.assign(zoomAnim, { activo: true, modo: 'libre', z: Math.min(ZOOM_MAX, Math.max(ZOOM_MIN, z)), ox, oy });
    sucio = true;
  }
  /** Un cuadro de la animación del zoom; devuelve true si el mapa cambió. */
  function pasoZoom() {
    if (!zoomAnim.activo) return false;
    const k = 0.2;
    vista.zoom += (zoomAnim.z - vista.zoom) * k;
    if (zoomAnim.modo === 'ancla') {
      const [nx, ny] = aPantalla(zoomAnim.mx, zoomAnim.my);
      vista.ox += zoomAnim.px - nx; vista.oy += zoomAnim.py - ny;
      if (Math.abs(zoomAnim.z - vista.zoom) < 0.001) zoomAnim.activo = false;
    } else {
      vista.ox += (zoomAnim.ox - vista.ox) * k; vista.oy += (zoomAnim.oy - vista.oy) * k;
      if (Math.abs(zoomAnim.z - vista.zoom) < 0.001 && Math.abs(zoomAnim.ox - vista.ox) < 0.3 && Math.abs(zoomAnim.oy - vista.oy) < 0.3) {
        vista.zoom = zoomAnim.z; vista.ox = zoomAnim.ox; vista.oy = zoomAnim.oy; zoomAnim.activo = false;
      }
    }
    return true;
  }
  $('zoomMas').onclick = () => acercar(1.4, cv.clientWidth / 2, cv.clientHeight / 2);
  $('zoomMenos').onclick = () => acercar(1 / 1.4, cv.clientWidth / 2, cv.clientHeight / 2);
  $('zoomReset').onclick = () => irA(1, 0, 0);
  document.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    if (unidadFiltrada() || (sel && sel.tipo === 'unidad')) limpiarFiltro();
    else if (sel) seleccionar(null);
  });

  // ================================================================ paneles plegables y menú
  document.querySelectorAll('[data-panel]').forEach((b) => b.addEventListener('click', () => {
    const panel = $(b.dataset.panel);
    const ocultar = !panel.hidden;
    const movil = window.matchMedia('(max-width: 760px)').matches;
    if (!ocultar && movil) {     // en celulares se muestra un panel a la vez
      document.querySelectorAll('.panel').forEach((p) => { p.hidden = true; });
      document.querySelectorAll('.reabrir').forEach((r) => { r.hidden = false; });
    }
    panel.hidden = ocultar;
    document.querySelector(`.reabrir[data-panel="${b.dataset.panel}"]`).hidden = !ocultar;
  }));
  /** v0.5: muestra un panel si estaba contraído (en celulares, cierra el otro). */
  function abrirPanel(id) {
    const panel = $(id);
    if (!panel.hidden) return;
    if (window.matchMedia('(max-width: 760px)').matches) {
      document.querySelectorAll('.panel').forEach((p) => { p.hidden = true; });
      document.querySelectorAll('.reabrir').forEach((r) => { r.hidden = false; });
    }
    panel.hidden = false;
    document.querySelector(`.reabrir[data-panel="${id}"]`).hidden = true;
  }
  $('plegarMenu').onclick = () => {
    $('app').classList.toggle('menu-plegado');
    setTimeout(ajustarTamano, 0);
  };

  // ================================================================ avisos y resumen final
  function avisar(texto, tipo = '') {
    const d = document.createElement('div');
    d.className = 'aviso ' + tipo;
    d.textContent = texto;
    $('avisos').appendChild(d);
    setTimeout(() => d.remove(), tipo === 'error' ? 7000 : 4500);
  }

  function revisarEventos(prev) {
    if (!estado || estado.estado === 'SIN_SIMULACION') return;
    const clave = estado.escenario + estado.inicio;
    if (prev && prev.estado !== estado.estado) {
      if (estado.estado === 'PAUSADA') avisar('Simulación detenida. Presiona Reanudar para continuar desde este instante.', 'alerta');
      if (estado.estado === 'EJECUTANDO' && prev.estado === 'PAUSADA') avisar('Simulación reanudada.');
    }
    if (estado.lineasConError > 0 && lineasAvisadas !== clave) {
      lineasAvisadas = clave;
      avisar(`${num(estado.lineasConError)} líneas de los archivos de datos no tienen el formato esperado y no se usaron. Revisa la consola del servidor.`, 'alerta');
    }
    avisosAverias(prev);
    if (estado.error && estado.error !== errorMostrado) avisar('El planificador falló en este ciclo; las unidades siguen con su plan anterior. ' + estado.error, 'error');
    errorMostrado = estado.error;
    if (estado.estado === 'TERMINADA' && finMostrado !== clave) {
      finMostrado = clave;
      if (estado.tipoFin === 'COLAPSO') avisarColapso();                                   // v04
      else if (estado.tipoFin === 'FIN_PERIODO' || estado.tipoFin === 'SIN_COLAPSO') mostrarResumenFinal();
      else avisar(estado.motivoFin, 'alerta');
    }
  }

  function mostrarResumenFinal() {
    const ini = fechaLocal(estado.inicio), fin = fechaLocal(estado.hora);
    $('finTitulo').textContent = estado.tipoFin === 'SIN_COLAPSO' ? 'Simulación hasta el colapso terminada sin colapso' : 'Simulación de 5 días terminada';
    $('finPeriodo').textContent = `Del ${fmtFecha(ini)} al ${fmtFecha(fin)} · duró ${fmtDur(estado.segundosReales)} en tiempo real · algoritmo ${estado.algoritmo}`;
    const fc = $('finColapso');
    fc.hidden = estado.tipoFin !== 'SIN_COLAPSO';
    fc.innerHTML = fc.hidden ? '' : `<p class="colapso-motivo ok">${esc(estado.motivoFin)}.</p>`;
    llenarIndicadoresFinales();
    cargarPlan();                                // v0.5: reporte de la última planificación estable (G08)
    $('modalFin').showModal();
  }

  // ================================================================ v04: colapso
  const fmtHoras = (h) => `${Number(h).toLocaleString('es-PE', { minimumFractionDigits: 1, maximumFractionDigits: 1 })} h`;
  /** Al colapsar: alerta roja que no se cierra sola, reporte del colapso y el mapa centrado en el pedido involucrado. */
  function avisarColapso() {
    const c = estado.colapso;
    if (alertaColapso) alertaColapso.remove();
    const d = document.createElement('div');
    d.className = 'aviso colapso';
    d.setAttribute('role', 'alert');
    const quien = c && c.pedido ? `el pedido ${c.pedido.id} no se entregó a tiempo` : (c ? c.detalle : estado.motivoFin);
    d.innerHTML = `<b>Colapso logístico</b><span>${esc(fmtFecha(fechaLocal(c ? c.hora : estado.hora)))} · Tc = ${c ? fmtHoras(c.tc) : '—'}: ${esc(quien)}.</span>
      <span class="acciones-aviso"><button class="btn peligro" data-accion="reporte">Ver reporte</button><button class="cerrar-aviso" data-accion="cerrar" aria-label="Cerrar alerta">×</button></span>`;
    d.addEventListener('click', (ev) => {
      const a = ev.target.closest('[data-accion]');
      if (!a) return;
      if (a.dataset.accion === 'reporte') mostrarReporteColapso();
      else { d.remove(); alertaColapso = null; }
    });
    $('avisos').appendChild(d);
    alertaColapso = d;
    if (c && c.pedido) { $('buscar').value = ''; botonLimpiar(); seleccionar({ tipo: 'pedido', id: c.pedido.id }, true); }
    mostrarReporteColapso();
  }
  /** Reporte del colapso: instante, Tc, criterio, pedido involucrado, estado de la flota e indicadores acumulados. */
  function mostrarReporteColapso() {
    const c = estado.colapso;
    const ini = fechaLocal(estado.inicio), fin = fechaLocal(c ? c.hora : estado.hora);
    $('finTitulo').textContent = 'Colapso logístico';
    $('finPeriodo').textContent = `Del ${fmtFecha(ini)} al ${fmtFecha(fin)} · duró ${fmtDur(estado.segundosReales)} en tiempo real · algoritmo ${estado.algoritmo}`;
    let html = '';
    if (c) {
      html += `<div class="colapso-cabecera"><div><b>${esc(fmtFecha(fin))}</b><span>Fecha y hora simulada del colapso</span></div>
        <div><b>${fmtHoras(c.tc)}</b><span>Tc · ${fmtDur(c.tc * 3600)} desde el inicio</span></div></div>
        <p class="colapso-motivo">Criterio ${esc(c.criterio)}: ${esc(c.descripcion)}.</p>`;
      if (c.pedido) {
        const p = c.pedido;
        const lleva = p.unidades.length ? p.unidades.map(esc).join(', ') : 'ninguna (sin asignar)';
        html += `<h3 class="colapso-sub">Pedido involucrado</h3>
          <dl class="colapso-pedido">
            <dt>Pedido</dt><dd>${esc(p.id)} · cliente ${esc(p.cliente)} · (${p.x}, ${p.y})</dd>
            <dt>Cantidad</dt><dd>${p.entregado} de ${p.cantidad} entregados</dd>
            <dt>Plazo</dt><dd>${p.plazo} h · registrado ${esc(fmtCorta(p.registro))} · límite ${esc(fmtCorta(p.limite))}</dd>
            <dt>Unidad asignada</dt><dd>${lleva}</dd>
          </dl>`;
      }
      const f = c.flota;
      html += `<h3 class="colapso-sub">Estado de la flota en ese instante</h3>
        <div class="colapso-flota">
          <div><b>${f.enRuta}</b><span>En ruta</span></div><div><b>${f.disponibles}</b><span>Disponibles</span></div>
          <div><b>${f.mantenimiento}</b><span>En mantenimiento</span></div>
          <div><b>${f.averiadasTipo1} · ${f.averiadasTipo2} · ${f.averiadasTipo3}</b><span>Averiadas (T1 · T2 · T3)</span></div>
        </div>
        <h3 class="colapso-sub">Indicadores hasta el colapso</h3>`;
    }
    const fc = $('finColapso');
    fc.innerHTML = html;
    fc.hidden = !html;
    llenarIndicadoresFinales();
    cargarPlan();                                // v0.5: reporte de la última planificación estable (G10)
    if (!$('modalFin').open) $('modalFin').showModal();
  }
  /** Indicadores acumulados del escenario (los mismos al terminar los 5 días, al colapsar o sin colapso). */
  function llenarIndicadoresFinales() {
    const r = estado.resumen;
    const celdas = [
      [num(r.entregados), 'Pedidos entregados'],
      [(r.entregados + r.vencidosSinEntregar ? r.pctATiempo.toFixed(1) : '100.0') + '%', 'Entregados a tiempo'],
      [num(r.vencidos), 'Pedidos vencidos'],
      [num(r.recibidos), 'Pedidos recibidos'],
      [num(r.pendientes), 'Pendientes al cierre'],
      [num(r.km) + ' km', 'Recorrido de la flota'],
      ['S/ ' + num(r.costo), 'Costo de transporte'],
      [num(estado.ciclos), 'Ciclos de planificación'],
      [num(estado.msMaximoPlan) + ' ms', 'Ta máximo (tiempo de ejecución del planificador)'],
      [`${num(estado.scMin)} min · ${Number(estado.saSeg).toLocaleString('es-PE', { maximumFractionDigits: 1 })} s`, 'Sc (salto de consumo) · Sa (salto del algoritmo)'],
      [num(r.averias), 'Averías (tipo 1, 2 y 3)'],
      [num(r.trasvasados), 'Paquetes trasvasados'],
      [num(r.devueltos), 'Devueltos al central'],
    ];
    $('finGrid').innerHTML = celdas.map(([v, t]) => `<div><b>${v}</b><span>${t}</span></div>`).join('');
  }
  $('finCerrar').onclick = () => {
    $('modalFin').close();
    const c = estado && estado.colapso;   // v04: al volver al mapa tras el colapso, queda elegido el pedido involucrado
    if (c && c.pedido && estado.estado === 'TERMINADA') seleccionar({ tipo: 'pedido', id: c.pedido.id }, true);
  };
  $('finNueva').onclick = () => { $('modalFin').close(); $('fecha').focus(); };

  // ================================================================ controles de la simulación
  async function post(url) {
    const r = await fetch(url, { method: 'POST' });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || r.statusText);
    return j;
  }
  async function iniciar() {
    const v = $('fecha').value;
    if (!v) { avisar('Elige la fecha y hora de inicio.', 'alerta'); $('fecha').focus(); return; }
    try {
      const m = modo;
      await post(`/api/simulacion?escenario=${m}&fecha=${encodeURIComponent(v)}`);
      sel = null; anterior = null; anim.clear(); finMostrado = ''; propias.clear();
      if (alertaColapso) { alertaColapso.remove(); alertaColapso = null; }
      $('buscar').value = ''; botonLimpiar();
      mostrarDetalle();
      avisar(`${m === 'COLAPSO' ? 'Simulación hasta el colapso' : 'Simulación de 5 días'} iniciada desde el ${fmtFecha(fechaLocal(v))}.`);
    } catch (e) { avisar('No se pudo iniciar: ' + e.message, 'error'); }
  }
  $('btnIniciar').onclick = () => {
    if (estado && (estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA')) $('modalConfirmar').showModal();
    else iniciar();
  };
  /** v04: selector "5 Días" / "Colapso" de la barra superior. */
  function elegirModo(m) {
    modo = m === 'COLAPSO' ? 'COLAPSO' : 'SEMANAL';
    $('modo5d').classList.toggle('activo', modo === 'SEMANAL');
    $('modo5d').setAttribute('aria-pressed', String(modo === 'SEMANAL'));
    $('modoColapso').classList.toggle('activo', modo === 'COLAPSO');
    $('modoColapso').setAttribute('aria-pressed', String(modo === 'COLAPSO'));
    actualizarPaneles();
  }
  $('modo5d').onclick = () => elegirModo('SEMANAL');
  $('modoColapso').onclick = () => elegirModo('COLAPSO');
  $('confNo').onclick = () => $('modalConfirmar').close();
  $('confSi').onclick = () => { $('modalConfirmar').close(); iniciar(); };
  $('btnDetener').onclick = () => post('/api/simulacion/detener').catch((e) => avisar(e.message, 'error'));
  $('btnReanudar').onclick = () => post('/api/simulacion/reanudar').catch((e) => avisar(e.message, 'error'));

  /**
   * v0.5 (B01): el tamaño del mapa viene del servidor: el de la simulación en curso (estado.mapa) o, si no hay
   * simulación, el configurado (/api/config). Si cambia, se vuelve a mostrar la ciudad completa.
   */
  function fijarMapa(m) {
    if (!m || !(m.ancho > 0) || !(m.alto > 0) || (m.ancho === ANCHO && m.alto === ALTO)) return;
    ANCHO = m.ancho; ALTO = m.alto;
    vista.zoom = 1; vista.ox = 0; vista.oy = 0;
    sucio = true;
  }

  /** Lee /api/config: leyendas del semáforo, reglas de averías y fecha propuesta (v0.5: también tras guardar la configuración). */
  function cargarConfigCliente() {
    return fetch('/api/config').then((r) => r.json()).then((c) => {
      configVersionVista = c.configVersion;
      if (!$('fecha').value) $('fecha').value = `${c.fechaInicio}T${c.horaInicio || '00:00'}`;
      if (!(estado && estado.mapa)) fijarMapa(c.mapa);   // v0.5 (B01): sin simulación, el mapa configurado
      if (c.semaforo) aplicarUmbrales(c.semaforo);   // v0.5: todas las leyendas del semáforo según la configuración
      if (c.averiaTipo1Horas !== undefined) {
        cfgAveria = { tipo1Horas: c.averiaTipo1Horas, enLugarHoras: c.averiaEnLugarHoras, tipo3Dias: c.averiaTipo3Dias,
          tipo3Hora: c.averiaTipo3Hora, trasvaseMin: c.averiaTrasvaseMinutos };
      }
    }).catch(() => { if (!$('fecha').value) $('fecha').value = '2026-01-01T00:00'; });
  }
  let configVersionVista = null;
  cargarConfigCliente();

  // ================================================================ conexión con el servidor (SSE)
  function conectar() {
    const es = new EventSource('/api/stream');
    es.onmessage = (m) => {
      const prev = estado;
      const ahora = performance.now();
      if (recibidoEn) intervalo = Math.max(200, Math.min(1500, ahora - recibidoEn));
      anterior = estado;
      estado = JSON.parse(m.data);
      if (estado.unidades) prepararAnimacion(); else anim.clear();
      if (estado.escenario && estado.estado !== 'SIN_SIMULACION' && estado.escenario + estado.inicio !== escenarioVisto) {
        escenarioVisto = estado.escenario + estado.inicio;   // v04: una simulación nueva fija el modo mostrado
        if (estado.escenario === 'COLAPSO' || estado.escenario === 'SEMANAL') elegirModo(estado.escenario);
        // v0.5: la fecha y hora de inicio también siguen a la simulación en curso (al abrir la página, en otra pestaña
        // o en otro dispositivo, y cuando otro navegador inicia una nueva)
        if (estado.inicio) $('fecha').value = String(estado.inicio).slice(0, 16);
      }
      if (estado.mapa) fijarMapa(estado.mapa);              // v0.5 (B01): mapa de la simulación en curso
      recibidoEn = ahora;
      $('estado').classList.remove('desconectado');
      if (estado.configVersion !== undefined && configVersionVista !== null && estado.configVersion !== configVersionVista) {
        configVersionVista = estado.configVersion;   // v0.5: la configuración cambió (aquí o en otro navegador)
        cargarConfigCliente();
        configCambioExterno();
      }
      actualizarPaneles();
      actualizarListas();
      actualizarDialogoAveria();
      mostrarDetalle();
      revisarEventos(prev);
      sucio = true;
    };
    es.onerror = () => {
      const chip = $('estado');
      chip.className = 'estado desconectado';
      chip.querySelector('span').textContent = 'Sin conexión';
      chip.title = 'Sin conexión';
    };
  }

  // ================================================================ bucle de dibujo
  function bucle() {
    const animando = estado && estado.estado === 'EJECUTANDO';
    const zoom = pasoZoom();                     // v0.5: zoom continuo
    if (sucio || animando || zoom) { dibujar(); sucio = false; }
    actualizarReloj();
    requestAnimationFrame(bucle);
  }


  // ================================================================ v0.5: horas, productos y envíos
  /** Fecha y hora de un instante de la simulación dado en segundos desde su inicio. */
  function fechaSim(seg) { const i = estado && fechaLocal(estado.inicio); return i ? new Date(i.getTime() + seg * 1000) : null; }
  function horaSim(seg) {
    if (seg === undefined || seg === null || seg < 0) return '—';
    const d = fechaSim(seg), hoy = fechaSim(estado.t);
    if (!d) return '—';
    const hm = `${dos(d.getHours())}:${dos(d.getMinutes())}`;
    return hoy && d.toDateString() === hoy.toDateString() ? hm : `${dos(d.getDate())}/${dos(d.getMonth() + 1)} ${hm}`;
  }
  function minutosTxt(min) {
    min = Math.max(0, Math.round(min));
    if (min < 60) return `${min} min`;
    return `${Math.floor(min / 60)} h ${dos(min % 60)} min`;
  }
  /** Identificadores de producto: P12-3, o P12-1 a P12-5. */
  const rango = (ped, p0, p1) => p0 === undefined ? '' : p0 === p1 ? `${ped}-${p0}` : `${ped}-${p0} a ${ped}-${p1}`;
  /** De dónde y cuándo salen los productos de un envío. */
  function textoSalida(e) {
    const de = esTrasvase(e.de) ? 'trasvase de ' + e.de.slice(4) : nombreAlmacen(e.de);
    if (e.sal < 0) return `desde ${de}`;
    return e.sal <= estado.t ? `salió de ${de} ${horaSim(e.sal)}` : `sale de ${de} ${horaSim(e.sal)}`;
  }
  /** Tabla de envíos de un pedido en el detalle: unidad, productos, ruta, salida y llegada (E31, F02 y F03). */
  function tablaEnvios(p) {
    if (!p.envios || !p.envios.length) return '<p class="nota">Aún no tiene envíos planificados.</p>';
    const filas = p.envios.map((e) => `<tr class="fila-envio${sel && sel.unidad === e.u ? ' sel' : ''}" data-unidad="${e.u}" title="Ver la ruta de estos productos">
        <td>${e.u}</td><td><code>${rango(p.id, e.p0, e.p1)}</code><br>${e.q} paq.</td>
        <td>${esc(textoSalida(e))}<br>parada ${e.orden} de ${e.u}</td><td>${horaSim(e.ll)}</td></tr>`).join('');
    return `<table class="envios-tabla"><thead><tr><th>Unidad</th><th>Productos</th><th>Ruta</th><th>Llega</th></tr></thead><tbody>${filas}</tbody></table>
      ${p.envios.length > 1 || (sel && sel.unidad) ? '<button class="ver-todas" type="button">Ver las rutas de todos sus productos</button>' : ''}`;
  }
  $('detalleCuerpo').addEventListener('click', (ev) => {
    if (!sel || sel.tipo !== 'pedido') return;
    const fila = ev.target.closest('.fila-envio');
    if (fila) { seleccionar({ tipo: 'pedido', id: sel.id, unidad: fila.dataset.unidad }, true); return; }   // F02
    if (ev.target.closest('.ver-todas')) seleccionar({ tipo: 'pedido', id: sel.id }, true);                 // F03
  });

  // ================================================================ v0.5: lista de unidades (E02 a E07, E13 a E15, F01)
  /** Destino inmediato y origen de una unidad, con los minutos a la llegada y a la salida. */
  function infoUnidad(u) {
    const t = estado.t;
    const aqui = (u.estado === 'ENTREGANDO' || u.estado === 'TRASVASE' || u.estado === 'REFRIGERIO') && u.ocupadoHasta > t;
    const prox = u.paradas[0] || null;
    let destino = null;
    if (u.averia) destino = null;
    else if (aqui) destino = { texto: u.estado === 'ENTREGANDO' ? `entregando en (${Math.round(u.x)}, ${Math.round(u.y)})` : u.estado === 'TRASVASE' ? 'trasvase en curso' : 'refrigerio',
      llega: t, sale: u.ocupadoHasta, enDestino: true };
    else if (prox) {
      // el refrigerio se toma al llegar al nodo de la parada siguiente: el destino es esa parada
      const real = prox.tipo === 'REFRIGERIO' && u.paradas[1] ? u.paradas[1] : prox;
      const texto = real.tipo === 'ENTREGA' ? `${real.ref} en (${real.x}, ${real.y})` : real.tipo === 'RECARGA' ? nombreAlmacen(real.ref) : 'refrigerio';
      destino = { texto: real !== prox ? texto + ' (antes, refrigerio de 1 h)' : texto, llega: prox.ll, sale: real.sa, enDestino: false };
    }
    const recarga = u.paradas.find((p) => p.tipo === 'RECARGA' && !esTrasvase(p.ref));
    return {
      destino,
      llegaMin: destino && !destino.enDestino ? Math.max(0, (destino.llega - t) / 60) : destino ? 0 : null,
      saleMin: destino ? Math.max(0, (destino.sale - t) / 60) : null,
      salioMin: u.salida >= 0 ? Math.max(0, (t - u.salida) / 60) : null,
      recarga, almLlegaMin: recarga ? Math.max(0, (recarga.ll - t) / 60) : null,
    };
  }
  const elementosUnidad = new Map();
  function actualizarListaUnidades() {
    if (pestana !== 'unidades') return;
    const ul = $('listaUnidades');
    let lista = estado && estado.unidades ? estado.unidades.filter(pasaUnidad) : [];
    const q = $('buscar').value.trim().toLowerCase();
    if (q) lista = lista.filter((u) => u.id.toLowerCase().includes(q) || u.paradas.some((p) => p.tipo === 'ENTREGA' && p.ref.toLowerCase() === q));
    const info = new Map(lista.map((u) => [u.id, infoUnidad(u)]));
    // búsqueda por minutos al destino inmediato o al origen, a la llegada o a la salida (E06, E07)
    const v = parseFloat($('minValor').value);
    const crit = $('minCriterio').value;
    if (!isNaN(v)) {
      lista = lista.filter((u) => {
        const i = info.get(u.id);
        const m = crit === 'destino-llegada' ? (i.destino && !i.destino.enDestino ? i.llegaMin : null)
          : crit === 'destino-salida' ? i.saleMin : crit === 'origen-salida' ? i.salioMin : i.almLlegaMin;
        return m !== null && m <= v;
      });
    }
    // orden por ocupación, hora de salida o de llegada, ascendente o descendente (E13 a E15)
    const orden = $('ordenUnidades').value;
    const fin = (x, asc) => (x === null || x === undefined || x < 0 ? (asc ? Infinity : -Infinity) : x);
    const clave = {
      'ocupacion-desc': (u) => -u.ocupacion, 'ocupacion-asc': (u) => u.ocupacion,
      'salida-asc': (u) => fin(u.salida, true), 'salida-desc': (u) => -fin(u.salida, false),
      'llegada-asc': (u) => fin(info.get(u.id).destino ? info.get(u.id).destino.llega : null, true),
      'llegada-desc': (u) => -fin(info.get(u.id).destino ? info.get(u.id).destino.llega : null, false),
    }[orden];
    lista = [...lista].sort((a, b) => (clave ? clave(a) - clave(b) : 0) || a.id.localeCompare(b.id));
    $('etiquetaUnidades').textContent = !isNaN(v) ? 'Coinciden con los minutos' : q ? 'Resultados' : 'Unidades';
    $('cuentaUnidades').textContent = `${num(lista.length)} ${lista.length === 1 ? 'unidad' : 'unidades'}`;
    const vistos = new Set();
    lista.forEach((u, i) => {
      let li = elementosUnidad.get(u.id);
      if (!li) { li = document.createElement('li'); li.className = 'pedido unidad-fila'; li.dataset.id = u.id; li.tabIndex = 0; elementosUnidad.set(u.id, li); }
      const html = htmlUnidad(u, info.get(u.id));
      if (li._html !== html) { li.innerHTML = html; li._html = html; }
      li.classList.toggle('sel', !!(sel && sel.tipo === 'unidad' && sel.id === u.id));
      if (ul.children[i] !== li) ul.insertBefore(li, ul.children[i] || null);
      vistos.add(u.id);
    });
    for (const [id, li] of elementosUnidad) if (!vistos.has(id)) { li.remove(); elementosUnidad.delete(id); }
    let vacia = ul.querySelector('.lista-vacia');
    if (!lista.length) {
      if (!vacia) { vacia = document.createElement('li'); vacia.className = 'lista-vacia'; ul.appendChild(vacia); }
      vacia.textContent = estado && estado.unidades ? 'Ninguna unidad coincide con los filtros o la búsqueda.' : 'Inicia una simulación para ver las unidades.';
    } else if (vacia) vacia.remove();
  }
  function htmlUnidad(u, i) {
    const pct = Math.floor(u.ocupacion);
    const chip = u.averia ? `<span class="chip AVERIADA">Avería tipo ${u.averia.tipo}</span>` : `<span class="chip ${u.estado}">${ESTADOS_U[u.estado] || u.estado}</span>`;
    let destino = '<span class="linea">Destino: <b>—</b></span>';
    if (u.averia) destino = `<span class="linea">No disponible hasta ${fmtCorta(u.averia.disponible)}</span>`;
    else if (u.estado === 'MANTENIMIENTO') destino = `<span class="linea">En mantenimiento hasta ${fmtCorta(u.noDisponibleHasta)}</span>`;
    else if (i.destino) destino = `<span class="linea">Destino: <b>${esc(i.destino.texto)}</b> · ${i.destino.enDestino
      ? `sale ${horaSim(i.destino.sale)} (en ${minutosTxt(i.saleMin)})`
      : `llega ${horaSim(i.destino.llega)} (en ${minutosTxt(i.llegaMin)}) · sale ${horaSim(i.destino.sale)}`}</span>`;
    let origen = `<span class="linea">Origen: <b>${esc(nombreAlmacen(u.origen))}</b>${i.salioMin !== null ? ` · salió ${horaSim(u.salida)} (hace ${minutosTxt(i.salioMin)})` : ' · aún no sale'}`;
    if (i.recarga) origen += ` · vuelve a ${esc(nombreAlmacen(i.recarga.ref))} en ${minutosTxt(i.almLlegaMin)}`;
    origen += '</span>';
    let envios = '';
    if (sel && sel.tipo === 'unidad' && sel.id === u.id) {   // fila elegida: envíos y productos que traslada (E04, E05)
      const ent = u.paradas.filter((p) => p.tipo === 'ENTREGA');
      envios = `<ul class="envios-u"><li>A bordo: <b>${u.carga}</b> de ${u.capacidad} paquetes</li>${ent.length ? ent.map((p) =>
        `<li><b>${p.ref}</b> · ${p.cantidad} paq. <code>${rango(p.ref, p.p0, p.p1)}</code> → (${p.x}, ${p.y}) · llega ${horaSim(p.ll)}</li>`).join('')
        : '<li>Sin envíos asignados.</li>'}</ul>`;
    }
    return `<span class="cod">${iconoUnidad(u.tipo)}${u.id} ${chip}</span>
      <span class="carga" title="Carga: ${u.carga} de ${u.capacidad} paquetes"><i class="p ${u.nivel}"></i>${u.carga}/${u.capacidad} · ${pct}%</span>
      <span class="riel-c"><span class="${u.nivel}" style="width:${pct}%"></span></span>${destino}${origen}${envios}`;
  }
  function elegirUnidadLista(ev) {
    const li = ev.target.closest('.unidad-fila');
    if (!li) return;
    if (sel && sel.tipo === 'unidad' && sel.id === li.dataset.id) seleccionar(null);
    else { quitarFiltroUnidad(); seleccionar({ tipo: 'unidad', id: li.dataset.id }, true); }   // F01: de la lista al mapa
  }
  $('listaUnidades').addEventListener('click', elegirUnidadLista);
  $('listaUnidades').addEventListener('keydown', (ev) => { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); elegirUnidadLista(ev); } });
  for (const id of ['ordenUnidades', 'minCriterio']) $(id).addEventListener('change', actualizarListaUnidades);
  $('minValor').addEventListener('input', actualizarListaUnidades);

  // ================================================================ v0.5: lista de almacenes (E18 a E22, F05)
  const abiertos = new Set();        // secciones desplegadas de cada almacén (se conservan al refrescar)
  function actualizarListaAlmacenesDer() {
    if (pestana !== 'almacenes') return;
    const ul = $('listaAlmacenesDer');
    if (!estado || !estado.almacenes) { ul.innerHTML = '<li class="lista-vacia">Inicia una simulación para ver los almacenes.</li>'; ul._html = ''; return; }
    const q = $('buscar').value.trim().toLowerCase();
    const coincide = (e) => !q || e.pedido.toLowerCase().includes(q) || e.u.toLowerCase().includes(q) || (e.cli || '').toLowerCase().includes(q);
    const h = Math.round(estado.ventanaHoras || 4);
    const html = estado.almacenes.filter(pasaAlmacen).map((a) => {
      const elegido = sel && sel.tipo === 'almacen' && sel.id === a.id;
      const stock = a.central ? '<span class="pct">∞</span>' : `<span class="pct t-${a.nivel}">${pctStock(a)}%</span>`;
      const icono = `<span class="t-${a.central ? 'NEUTRO' : a.nivel}">${CASA}</span>`;
      const sub = a.central ? 'Abastecimiento permanente' : `${num(a.stock)} de ${num(a.capacidad)} paquetes${a.nivel === 'VACIO' ? ' · vacío' : ''}`;
      const seccion = (k, titulo, datos, fila) => {
        const lista = datos.lista.filter(coincide);
        const filas = lista.length ? lista.map(fila).join('') + (datos.envios > datos.lista.length ? `<li class="vacia">y ${num(datos.envios - datos.lista.length)} envíos más</li>` : '')
          : `<li class="vacia">${q ? 'Ninguno coincide con la búsqueda.' : 'Ninguno.'}</li>`;
        return `<details data-k="${a.id}:${k}"${abiertos.has(a.id + ':' + k) ? ' open' : ''}><summary><span>${titulo}</span><b>${num(datos.envios)} envíos · ${num(datos.paquetes)} paq.</b></summary><ul class="alm-lista">${filas}</ul></details>`;
      };
      const envio = (extra) => (e) => `<li><b>${e.pedido}</b> · ${e.u} · ${e.q} paq. <code>${rango(e.pedido, e.p0, e.p1)}</code> → (${e.x}, ${e.y}) · ${extra(e)}</li>`;
      return `<li class="alm-card${elegido ? ' sel' : ''}" data-id="${a.id}">
        <div class="alm-cab" title="Ver en el mapa">${icono}<span>${esc(a.nombre)}</span>${stock}</div>
        <div class="sub">${sub} · a bordo de unidades: ${num(a.aBordo)} paq. · vienen a recargar: ${a.recargan.length}</div>
        ${seccion('transito', 'En tránsito', a.transito, envio((e) => `llega ${horaSim(e.ll)}`))}
        ${seccion('porSalir', `Salen en las próximas ${h} h`, a.porSalir, envio((e) => `sale ${horaSim(e.sal)} · llega ${horaSim(e.ll)}`))}
        ${seccion('entregados', `Entregados en las últimas ${h} h`, a.entregados, envio((e) => `entregado ${horaSim(e.hora)}`))}
      </li>`;
    }).join('') || '<li class="lista-vacia">Ningún almacén coincide con los filtros.</li>';
    if (ul._html !== html) { ul.innerHTML = html; ul._html = html; }
  }
  $('listaAlmacenesDer').addEventListener('toggle', (ev) => {
    const d = ev.target;
    if (!d.dataset || !d.dataset.k) return;
    if (d.open) abiertos.add(d.dataset.k); else abiertos.delete(d.dataset.k);
  }, true);
  $('listaAlmacenesDer').addEventListener('click', (ev) => {
    const cab = ev.target.closest('.alm-cab');
    if (!cab) return;
    const id = cab.closest('.alm-card').dataset.id;
    if (sel && sel.tipo === 'almacen' && sel.id === id) seleccionar(null);
    else { quitarFiltroUnidad(); seleccionar({ tipo: 'almacen', id }, true); }       // F05: de la lista al mapa
  });

  // ================================================================ v0.5: filtros globales del mapa (D05 a D07)
  function enlazarFiltro(idGrupo, conjunto) {
    $(idGrupo).addEventListener('click', (ev) => {
      const b = ev.target.closest('.bf');
      if (!b) return;
      if (conjunto.has(b.dataset.v)) conjunto.delete(b.dataset.v); else conjunto.add(b.dataset.v);
      refrescarFiltros();
    });
  }
  enlazarFiltro('fTipo', filtros.tipo);
  enlazarFiltro('fCarga', filtros.carga);
  enlazarFiltro('fAlmacen', filtros.almacen);
  function refrescarFiltros() {
    let apagados = 0;
    for (const [id, conjunto] of [['fTipo', filtros.tipo], ['fCarga', filtros.carga], ['fAlmacen', filtros.almacen]]) {
      $(id).querySelectorAll('.bf').forEach((b) => {
        const on = conjunto.has(b.dataset.v);
        if (!on) apagados++;
        b.classList.toggle('activo', on);
        b.setAttribute('aria-pressed', String(on));
      });
    }
    $('nFiltros').hidden = apagados === 0;
    $('nFiltros').textContent = apagados ? `${apagados} ocultos` : '';
    if (estado && estado.unidades) $('nUnidades').textContent = num(estado.unidades.filter(pasaUnidad).length);
    actualizarListas();
    sucio = true;
  }
  $('limpiarFiltros').onclick = () => {
    for (const [c, v] of [[filtros.tipo, ['AUTO', 'MOTO', 'BICI']], [filtros.carga, ['VERDE', 'AMBAR', 'ROJO', 'VACIO']], [filtros.almacen, ['VERDE', 'AMBAR', 'ROJO', 'VACIO']]]) v.forEach((x) => c.add(x));
    refrescarFiltros();
  };

  // ================================================================ v0.5: leyendas según la configuración
  const fmtNum = (v) => Number(v).toLocaleString('es-PE', { maximumFractionDigits: 1 });
  function aplicarUmbrales(u) {
    umbrales = u;
    const ped = u.pedido;
    const a = fmtNum((1 - ped.verde) * 100), b = fmtNum((1 - ped.ambar) * 100);
    $('leyendaPedidos').innerHTML = `<span class="nota">Plazo transcurrido</span><span><i class="p VERDE"></i>&lt;${a}%</span><span><i class="p AMBAR"></i>${a}–${b}%</span><span><i class="p ROJO"></i>&gt;${b}% o vencido</span>`;
    const tramo = (x, nombre) => `<span class="nota">${nombre}</span><span><i class="p VERDE"></i>≥${fmtNum(x.verde)}%</span><span><i class="p AMBAR"></i>≥${fmtNum(x.ambar)}%</span><span><i class="p ROJO"></i>&lt;${fmtNum(x.ambar)}%</span><span><i class="p VACIO"></i>vacío</span>`;
    $('leyendaCargaLista').innerHTML = tramo(u.carga, 'Carga');
    $('leyendaGlobal').innerHTML = `<div class="leyenda-mini">${tramo(u.flota, 'Flota')}</div><div class="leyenda-mini">${tramo(u.almacen, 'Almacenes')}</div>`;
    $('leyCarga').textContent = `Carga de la unidad (barra bajo el ícono): verde desde ${fmtNum(u.carga.verde)}%, ámbar desde ${fmtNum(u.carga.ambar)}%, rojo menos, contorno gris vacía`;
    $('lsVerde').textContent = `desde ${fmtNum(u.almacen.verde)}%`;
    $('lsAmbar').textContent = `desde ${fmtNum(u.almacen.ambar)}%`;
    $('lsRojo').textContent = `menos de ${fmtNum(u.almacen.ambar)}%`;
  }

  // ================================================================ v0.5: reporte de la última planificación estable (G08, G10)
  let planReporte = null;
  async function cargarPlan() {
    const sec = $('finPlan');
    sec.hidden = false;
    sec.innerHTML = '<h3>Última planificación estable</h3><p class="nota">Cargando…</p>';
    try {
      const r = await (await fetch('/api/plan')).json();
      planReporte = r.plan;
      if (!planReporte) { sec.innerHTML = '<h3>Última planificación estable</h3><p class="nota">No se llegó a calcular ninguna planificación en este escenario.</p>'; return; }
      const porUnidad = new Map();
      for (const f of planReporte.filas) { if (!porUnidad.has(f.unidad)) porUnidad.set(f.unidad, []); porUnidad.get(f.unidad).push(f); }
      const sinParadas = estado.unidades.filter((u) => !porUnidad.has(u.id)).map((u) => u.id);
      const unidades = [...porUnidad.entries()].map(([id, filas]) => {
        const ent = filas.filter((f) => f.tipo === 'ENTREGA');
        return `<details><summary>${iconoUnidad(filas[0].tipoUnidad)}<b>${id}</b><span>${ent.length} ${ent.length === 1 ? 'entrega' : 'entregas'} · ${ent.reduce((s, f) => s + f.cantidad, 0)} paq.</span><small>llega a la última parada ${fmtCorta(filas[filas.length - 1].llegada)}</small></summary>
          <table><thead><tr><th>#</th><th>Parada</th><th>Referencia</th><th>Destino</th><th>Cant.</th><th>Productos</th><th>Llega</th><th>Sale</th></tr></thead><tbody>${filas.map((f) =>
            `<tr><td>${f.orden}</td><td>${TIPO_PARADA[f.tipo] || f.tipo}</td><td>${esc(f.ref)}${f.cliente ? ' · ' + esc(f.cliente) : ''}</td><td>(${f.x}, ${f.y})</td><td>${f.cantidad || ''}</td><td><code>${esc(f.productos)}</code></td><td>${fmtCorta(f.llegada)}</td><td>${fmtCorta(f.salida)}</td></tr>`).join('')}</tbody></table></details>`;
      }).join('');
      sec.innerHTML = `<h3>Última planificación estable</h3>
        <p class="nota">Plan calculado el ${esc(fmtFecha(fechaLocal(planReporte.hora)))} (ciclo ${planReporte.ciclo}, ${esc(planReporte.algoritmo)}): itinerario de cada unidad con la hora estimada de llegada y salida de cada parada${planReporte.sinAsignar ? ` · ${planReporte.sinAsignar} partes de pedidos quedaron sin asignar en ese ciclo` : ''}.</p>
        <div class="acciones-plan"><a class="btn" href="/api/plan.csv" download="ultima-planificacion.csv">Descargar CSV</a><button class="btn" type="button" id="imprimirPlan">Imprimir</button></div>
        <div class="lista-plan">${unidades || '<p class="nota">Ninguna unidad tenía paradas.</p>'}</div>
        ${sinParadas.length ? `<p class="nota">Sin paradas en ese plan: ${sinParadas.join(', ')}.</p>` : ''}`;
      $('imprimirPlan').onclick = imprimirPlan;
    } catch (e) {
      sec.innerHTML = '<h3>Última planificación estable</h3><p class="nota">No se pudo obtener la planificación: ' + esc(e.message) + '</p>';
    }
  }
  const TIPO_PARADA = { ENTREGA: 'Entrega', RECARGA: 'Recarga', REFRIGERIO: 'Refrigerio' };
  function imprimirPlan() {
    if (!planReporte) return;
    const w = window.open('', '_blank');
    if (!w) { avisar('El navegador bloqueó la ventana de impresión; usa Descargar CSV.', 'alerta'); return; }
    const filas = planReporte.filas.map((f) => `<tr><td>${f.unidad}</td><td>${f.orden}</td><td>${TIPO_PARADA[f.tipo] || f.tipo}</td><td>${esc(f.ref)}</td><td>${esc(f.cliente)}</td><td>(${f.x}, ${f.y})</td><td>${f.cantidad || ''}</td><td>${esc(f.productos)}</td><td>${fmtCorta(f.llegada)}</td><td>${fmtCorta(f.salida)}</td></tr>`).join('');
    w.document.write(`<!doctype html><html lang="es"><head><meta charset="utf-8"><title>Última planificación estable</title>
      <style>body{font:12px system-ui,sans-serif;margin:20px}h1{font-size:16px;margin:0 0 4px}p{color:#555;margin:0 0 12px}table{border-collapse:collapse;width:100%}th,td{border:1px solid #ccc;padding:3px 5px;text-align:left}th{background:#f1f5f9}</style></head>
      <body><h1>RUTAPAQ · Última planificación estable</h1><p>Plan del ${esc(fmtFecha(fechaLocal(planReporte.hora)))} · ciclo ${planReporte.ciclo} · ${esc(planReporte.algoritmo)} · escenario ${planReporte.escenario === 'COLAPSO' ? 'hasta el colapso' : 'simulación de 5 días'}</p>
      <table><thead><tr><th>Unidad</th><th>#</th><th>Parada</th><th>Referencia</th><th>Cliente</th><th>Destino</th><th>Cant.</th><th>Productos</th><th>Llega</th><th>Sale</th></tr></thead><tbody>${filas}</tbody></table></body></html>`);
    w.document.close();
    w.focus();
    w.print();
  }


  // ================================================================ v0.5: pantalla Configuración (B01, B02, B04, B06 a B09)
  let cfgDatos = null;
  let cfgGuardando = false;
  const BADGE = { AHORA: 'Ahora', CICLO: 'Siguiente ciclo', INICIO: 'Al iniciar' };
  const camposCfg = () => (cfgDatos ? cfgDatos.secciones.flatMap((s) => s.campos) : []);
  function abrirConfig() {
    $('navSimulador').classList.remove('activo'); $('navSimulador').removeAttribute('aria-current');
    $('navConfig').classList.add('activo'); $('navConfig').setAttribute('aria-current', 'page');
    $('vistaConfig').hidden = false;
    cargarConfig();
  }
  function cerrarConfig() {
    if (hayCambiosCfg() && !window.confirm('Hay cambios sin guardar en la configuración. ¿Salir sin guardarlos?')) return;
    $('navConfig').classList.remove('activo'); $('navConfig').removeAttribute('aria-current');
    $('navSimulador').classList.add('activo'); $('navSimulador').setAttribute('aria-current', 'page');
    $('vistaConfig').hidden = true;
    sucio = true;
  }
  $('navConfig').onclick = abrirConfig;
  $('navSimulador').onclick = () => { if (!$('vistaConfig').hidden) cerrarConfig(); };
  $('cfgCerrar').onclick = cerrarConfig;

  async function cargarConfig() {
    try {
      cfgDatos = await (await fetch('/api/configuracion')).json();
      $('cfgAviso').hidden = true;
      dibujarConfig();
    } catch (e) {
      $('cfgSecciones').innerHTML = '<p class="nota">No se pudo leer la configuración: ' + esc(e.message) + '</p>';
    }
  }
  function entradaCampo(c) {
    const dis = `data-clave="${c.clave}"`;
    if (c.tipo === 'PUNTO') {
      const [x, y] = String(c.valor).split(',');
      return `<span class="unidad">x</span><input class="eje" type="number" min="0" step="1" value="${esc(x)}" ${dis} data-eje="x" aria-label="${esc(c.etiqueta)} x">`
        + `<span class="unidad">y</span><input class="eje" type="number" min="0" step="1" value="${esc(y)}" ${dis} data-eje="y" aria-label="${esc(c.etiqueta)} y"><span class="unidad">${esc(c.unidad || '')}</span>`;
    }
    if (c.tipo === 'HORA') return `<input type="time" step="60" value="${esc(c.valor)}" ${dis} aria-label="${esc(c.etiqueta)}">`;
    if (c.tipo === 'BOOLEANO') return `<input type="checkbox" ${c.valor === 'true' ? 'checked' : ''} ${dis} aria-label="${esc(c.etiqueta)}"><span class="unidad">${c.valor === 'true' ? 'Sí' : 'No'}</span>`;
    return `<input type="number" min="${c.min}" max="${c.max}" step="${c.paso}" value="${esc(c.valor)}" ${dis} aria-label="${esc(c.etiqueta)}">`
      + (c.unidad ? `<span class="unidad">${esc(c.unidad)}</span>` : '');
  }
  function htmlCampo(c) {
    const ancho = c.tipo === 'PUNTO' || (c.ayuda && c.ayuda.length > 40) ? ' ancho' : '';
    return `<label class="cfg-campo${ancho}" data-campo="${c.clave}">
      <span class="cfg-et">${esc(c.etiqueta)} <i class="badge ${c.aplica}" title="${esc(c.aplicaTexto)}">${BADGE[c.aplica]}</i></span>
      <span class="cfg-entrada">${entradaCampo(c)}</span>
      <small class="cfg-error" hidden></small>
      ${c.ayuda ? `<small class="cfg-ayuda">${esc(c.ayuda)}</small>` : ''}
      ${c.valor !== c.defecto ? `<small class="cfg-defecto">Por defecto: ${esc(c.tipo === 'BOOLEANO' ? (c.defecto === 'true' ? 'sí' : 'no') : c.defecto)}</small>` : ''}
    </label>`;
  }
  function dibujarConfig() {
    const html = cfgDatos.secciones.map((s) => {
      const grupos = [];
      for (const c of s.campos) {
        const g = c.grupo || '';
        if (!grupos.length || grupos[grupos.length - 1].g !== g) grupos.push({ g, campos: [] });
        grupos[grupos.length - 1].campos.push(c);
      }
      const cuerpo = grupos.map((gr) => gr.g
        ? `<div class="cfg-grupo"><h4>${esc(gr.g)}</h4><div class="cfg-campos">${gr.campos.map(htmlCampo).join('')}</div></div>`
        : `<div class="cfg-campos">${gr.campos.map(htmlCampo).join('')}</div>`).join('');
      return `<section class="cfg-card" data-seccion="${s.id}"><h3>${esc(s.titulo)}<span class="item">${esc(s.item)}</span></h3>
        <p class="nota">${esc(s.nota)}</p>${cuerpo}</section>`;
    }).join('');
    $('cfgSecciones').innerHTML = html;
    actualizarBotonesCfg();
  }
  /** Valor que muestra la pantalla para una clave (x,y en los puntos; true/false en los booleanos). */
  function leerCampoCfg(c) {
    const ins = [...$('cfgSecciones').querySelectorAll(`[data-clave="${c.clave}"]`)];
    if (!ins.length) return c.valor;
    if (c.tipo === 'PUNTO') return `${ins.find((i) => i.dataset.eje === 'x').value.trim()},${ins.find((i) => i.dataset.eje === 'y').value.trim()}`;
    if (c.tipo === 'BOOLEANO') return ins[0].checked ? 'true' : 'false';
    return ins[0].value.trim();
  }
  const mismoValor = (c, a, b) => (c.tipo === 'ENTERO' || c.tipo === 'DECIMAL') && a !== '' && b !== '' && !isNaN(a) && !isNaN(b) ? Number(a) === Number(b) : a === b;
  function cambiosCfg() {
    const r = {};
    for (const c of camposCfg()) { const v = leerCampoCfg(c); if (!mismoValor(c, v, c.valor)) r[c.clave] = v; }
    return r;
  }
  const hayCambiosCfg = () => !$('vistaConfig').hidden && Object.keys(cambiosCfg()).length > 0;
  function actualizarBotonesCfg() {
    const cambios = cambiosCfg();
    for (const c of camposCfg()) {
      const el = $('cfgSecciones').querySelector(`[data-campo="${c.clave}"]`);
      if (el) el.classList.toggle('cambiado', c.clave in cambios);
    }
    const n = Object.keys(cambios).length;
    $('cfgGuardar').disabled = n === 0 || cfgGuardando;
    $('cfgDescartar').disabled = n === 0;
    $('cfgGuardar').textContent = n ? `Guardar (${n})` : 'Guardar';
  }
  $('cfgSecciones').addEventListener('input', (ev) => {
    const campo = ev.target.closest('.cfg-campo');
    if (campo) { campo.classList.remove('error'); campo.querySelector('.cfg-error').hidden = true; }
    if (ev.target.type === 'checkbox') ev.target.nextElementSibling.textContent = ev.target.checked ? 'Sí' : 'No';
    actualizarBotonesCfg();
  });
  $('cfgSecciones').addEventListener('change', actualizarBotonesCfg);
  $('cfgDescartar').onclick = () => dibujarConfig();

  /** Diálogo de confirmación: qué se aplica ahora, en el siguiente ciclo y al iniciar la próxima simulación. */
  let accionCfg = null;
  function resumenCfg(claves, titulo, boton, accion) {
    const porClave = new Map(camposCfg().map((c) => [c.clave, c]));
    const seccionDe = new Map(cfgDatos.secciones.flatMap((s) => s.campos.map((c) => [c.clave, s.titulo])));
    const enCurso = estado && (estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA');
    const grupos = { AHORA: [], CICLO: [], INICIO: [] };
    for (const k of claves) { const c = porClave.get(k); if (c) grupos[c.aplica].push((c.grupo || seccionDe.get(k)) + ' · ' + c.etiqueta); }
    const textos = enCurso
      ? { AHORA: 'Se aplican ahora en la simulación en curso', CICLO: 'Se aplican en el siguiente ciclo de planificación', INICIO: 'Se aplican al iniciar la próxima simulación' }
      : { AHORA: 'Se aplican de inmediato', CICLO: 'Se aplican de inmediato', INICIO: 'Se aplican al iniciar la próxima simulación' };
    $('cfgConfTitulo').textContent = titulo;
    $('cfgResumen').innerHTML = `<p>Se guardará en <code>config.properties</code>. ${enCurso ? 'Hay una simulación en curso: el cambio afecta a todos los navegadores que la están viendo.' : 'Afecta a todos los navegadores conectados.'}</p>`
      + Object.entries(grupos).filter(([, l]) => l.length).map(([a, l]) => `<h4><i class="badge ${a}">${BADGE[a]}</i>${textos[a]}</h4><ul>${l.map((x) => `<li>${esc(x)}</li>`).join('')}</ul>`).join('');
    $('cfgSi').textContent = boton;
    accionCfg = accion;
    $('modalCfg').showModal();
  }
  $('cfgGuardar').onclick = () => {
    const cambios = cambiosCfg();
    if (!Object.keys(cambios).length) return;
    resumenCfg(Object.keys(cambios), '¿Guardar la configuración?', 'Guardar', () => enviarCfg('/api/configuracion', cambios));
  };
  $('cfgRestaurar').onclick = () => {
    const distintos = camposCfg().filter((c) => !mismoValor(c, c.valor, c.defecto)).map((c) => c.clave);
    if (!distintos.length) { avisar('La configuración ya tiene los valores por defecto.'); return; }
    resumenCfg(distintos, '¿Restaurar los valores por defecto?', 'Restaurar', () => enviarCfg('/api/configuracion/restaurar', {}));
  };
  $('cfgNo').onclick = () => $('modalCfg').close();
  $('cfgSi').onclick = () => { $('modalCfg').close(); if (accionCfg) accionCfg(); };
  async function enviarCfg(url, datos) {
    cfgGuardando = true;
    actualizarBotonesCfg();
    try {
      const r = await fetch(url, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(datos).toString() });
      const j = await r.json().catch(() => ({}));
      if (r.status === 400 && j.errores) {
        for (const [k, msg] of Object.entries(j.errores)) {
          const el = $('cfgSecciones').querySelector(`[data-campo="${k}"]`);
          if (el) { el.classList.add('error'); const e = el.querySelector('.cfg-error'); e.textContent = msg; e.hidden = false; }
        }
        const primero = $('cfgSecciones').querySelector('.cfg-campo.error');
        if (primero) primero.scrollIntoView({ block: 'center' });
        avisar('No se guardó: revisa los campos marcados en rojo.', 'error');
        return;
      }
      if (!r.ok) throw new Error(j.error || r.statusText);
      const partes = [];
      if (j.ahora.length) partes.push(`ahora: ${j.ahora.length}`);
      if (j.ciclo.length) partes.push(`en el siguiente ciclo: ${j.ciclo.length}`);
      if (j.inicio.length) partes.push(`al iniciar la próxima simulación: ${j.inicio.length}`);
      avisar(j.cambios ? `Configuración guardada (${partes.join(' · ')}).` : 'No había cambios que guardar.');
      $('cfgArchivo').textContent = j.archivo || 'config.properties';
      await cargarConfig();
      await cargarConfigCliente();
    } catch (e) {
      avisar('No se pudo guardar la configuración: ' + e.message, 'error');
    } finally {
      cfgGuardando = false;
      if (cfgDatos) actualizarBotonesCfg();
    }
  }
  /** Otro navegador guardó la configuración: se recarga la pantalla, o se avisa si aquí hay cambios sin guardar. */
  function configCambioExterno() {
    if ($('vistaConfig').hidden || cfgGuardando) return;
    if (!hayCambiosCfg()) { cargarConfig(); return; }
    $('cfgAviso').textContent = 'Otro navegador guardó cambios en la configuración. Tus cambios aún no se guardaron; si los descartas verás los valores nuevos.';
    $('cfgAviso').hidden = false;
  }
  $('cfgDescartar').addEventListener('click', () => { if (!$('cfgAviso').hidden) cargarConfig(); });

  // ================================================================ arranque
  let temaGuardado = null;
  try { temaGuardado = localStorage.getItem('rutapaq.tema'); } catch (e) { /* sin almacenamiento */ }
  aplicarTema(temaGuardado === 'light' ? 'light' : 'dark');
  if (window.matchMedia('(max-width: 760px)').matches) {      // en celulares el mapa empieza libre
    document.querySelectorAll('.panel').forEach((p) => { p.hidden = true; });
    document.querySelectorAll('.reabrir').forEach((r) => { r.hidden = false; });
  }
  new ResizeObserver(ajustarTamano).observe(cv);
  ajustarTamano();
  actualizarPaneles();
  conectar();
  requestAnimationFrame(bucle);
})();
