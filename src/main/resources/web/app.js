// RUTAPAQ v04 – visualizador del Simulador de rutas: simulación de 5 días y hasta el colapso, con averías de las unidades.
// Recibe el estado completo por SSE (/api/stream) cada ~500 ms y lo dibuja en un canvas.
// Toda la lógica (simulación, planificación, semáforo) está en el servidor: aquí solo se dibuja.
// Entre una foto y la siguiente, las unidades se interpolan siguiendo su camino por las calles,
// para que se desplacen de forma continua y sin saltos.
(() => {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const ANCHO = 70, ALTO = 50, MARGEN = 34;
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
    col.VERDE = col.verde; col.AMBAR = col.ambar; col.ROJO = col.rojo; col.NEUTRO = col['texto-2'];
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
  function rumbo(u, pos) {           // hacia dónde mira la unidad: -1 izquierda, 1 derecha
    const sig = u.camino[0];
    if (!sig) return 1;
    return sig[0] < pos[0] - 1e-6 ? -1 : 1;
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
    if (sel.tipo === 'pedido') { const p = estado.pedidos.find((x) => x.id === sel.id); return new Set(p ? p.unidades : []); }
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
      return { unidades: new Set(p.unidades), pedidos: new Set([p.id]) };
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
      const texto = a.central ? a.nombre : `${a.nombre} · ${num(a.stock)}`;
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
      glifo(u.tipo, px, py, c, rumbo(u, gr.pos), foco ? 1.25 : 1);
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
    const enLugar = gr.unidades.some((x) => x.averia && x.averia.fase === 'EN_LUGAR');
    const latido = enLugar && estado.estado === 'EJECUTANDO' ? (Math.sin(performance.now() / 260) + 1) / 2 : 0;
    ctx.fillStyle = hexA(col.rojo, enLugar ? 0.14 + 0.12 * latido : 0.1);
    ctx.beginPath(); ctx.arc(px, py, (foco ? 19 : 16) + 3 * latido, 0, 7); ctx.fill();
    ctx.fillStyle = hexA(col.fondo, 0.86); ctx.beginPath(); ctx.arc(px, py, foco ? 14 : 11.5, 0, 7); ctx.fill();
    ctx.strokeStyle = foco ? col.cian : col.rojo; ctx.lineWidth = foco ? 2.2 : 1.6; ctx.stroke();
    glifo(u.tipo, px, py, col['texto-2'], 1, foco ? 1.25 : 1);
    // distintivo relleno con el tipo de avería; con varias averiadas juntas, de contorno y con la cantidad
    const varias = gr.unidades.length > 1;
    ctx.fillStyle = varias ? col.fondo : col.rojo; ctx.beginPath(); ctx.arc(px + 11, py - 10, 8, 0, 7); ctx.fill();
    ctx.strokeStyle = varias ? col.rojo : col.fondo; ctx.lineWidth = 1.5; ctx.stroke();
    ctx.fillStyle = varias ? col.rojo : '#fff'; ctx.font = 'bold 10px system-ui'; ctx.textAlign = 'center';
    ctx.fillText(varias ? String(gr.unidades.length) : String(u.averia ? u.averia.tipo : '!'), px + 11, py - 6.5);
    if (foco && sel && sel.tipo === 'unidad') {
      ctx.font = '600 12px "Cascadia Mono", Consolas, monospace'; ctx.fillStyle = col.texto; ctx.textAlign = 'left';
      ctx.fillText(sel.id, px + 21, py - 12);
    }
  }
  function enAlmacen(pos) { return estado.almacenes.some((a) => Math.abs(a.x - pos[0]) < 0.01 && Math.abs(a.y - pos[1]) < 0.01); }

  /** Ícono de auto, moto o bicicleta (vista lateral), mirando a la derecha (sentido 1) o a la izquierda (-1). */
  function glifo(tipo, x, y, color, sentido, esc) {
    ctx.save();
    ctx.translate(x, y); ctx.scale(sentido * esc, esc);
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

    if (sinSim) return;
    const r = estado.resumen;
    $('kEntregados').textContent = num(r.entregados);
    const kt = $('kATiempo');
    kt.textContent = (r.entregados + r.vencidosSinEntregar) ? r.pctATiempo.toFixed(1) + '%' : '—';
    kt.className = 't-' + r.nivelATiempo;
    kt.title = `${num(r.aTiempo)} a tiempo de ${num(r.entregados + r.vencidosSinEntregar)} pedidos que ya debían llegar`;

    const htmlAlm = estado.almacenes.map((a) => a.central
      ? `<li data-id="${a.id}"><i class="p CENTRAL"></i><span>${esc(a.nombre)}</span><span class="stock">ilimitado</span><span></span></li>`
      : `<li data-id="${a.id}" title="${num(a.stock)} de ${num(a.capacidad)} unidades en stock"><i class="p ${a.nivel}"></i><span>${esc(a.nombre)}</span><span class="stock">${num(a.stock)} u.</span><span class="pct t-${a.nivel}">${Math.round(a.consumoPct)}%</span></li>`).join('');
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
    $('nAverias').textContent = num(r.unidadesAveriadas);
    $('tabAverias').classList.toggle('hay', r.unidadesAveriadas > 0);
    $('dKm').textContent = num(r.km) + ' km';
    $('dCosto').textContent = 'S/ ' + num(r.costo);
  }

  function actualizarReloj() {
    if (!estado || estado.estado === 'SIN_SIMULACION') {
      $('reloj').textContent = '--/--/---- --:--:--';
      $('tReal').textContent = 'Real 00:00:00'; $('tHoy').textContent = '';
      return;
    }
    const corre = estado.estado === 'EJECUTANDO';
    const dt = corre ? Math.min(performance.now() - recibidoEn, 1500) / 1000 : 0;
    const base = fechaLocal(estado.hora);
    const sim = base ? new Date(base.getTime() + dt * estado.factor * 1000) : null;
    $('reloj').textContent = fmtFecha(sim);
    if ($('modalAveria').open) $('avMomento').textContent = `La avería ocurre en el instante actual de la simulación: ${fmtFecha(sim)}.`;
    $('tReal').textContent = 'Real ' + fmtDur(estado.segundosReales + dt);
    $('tHoy').textContent = 'Hoy ' + fmtFecha(new Date());
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
    const unidades = p.unidades.length
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
    actualizarLista();
    actualizarListaAverias();
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
    actualizarLista();
    actualizarListaAverias();
    sucio = true;
  }
  function centrarEn(s) {
    const p = ubicacion(s);
    if (!p) return;
    if (vista.zoom < 2.5) vista.zoom = 2.5;
    const g0 = geom();
    const [px, py] = aPantalla(p[0], p[1], g0);
    vista.ox += g0.w / 2 - px; vista.oy += g0.h * 0.36 - py;   // un poco arriba: abajo va la tarjeta de detalle
    sucio = true;
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
        return `<li><b>${p.tipo === 'ENTREGA' ? ++n : trv ? 'T' : p.tipo === 'RECARGA' ? 'R' : 'C'}</b>${
          p.tipo === 'ENTREGA' ? `Entregar ${p.cantidad} a ${p.ref} en (${p.x}, ${p.y})`
            : trv ? `Recoger ${p.cantidad} paquetes de ${p.ref.slice(4)} (trasvase)`
              : p.tipo === 'RECARGA' ? `Recargar en ${nombreAlmacen(p.ref)}` : 'Refrigerio (1 h)'}</li>`;
      }).join('')}${u.paradas.length > 8 ? `<li><b></b>y ${u.paradas.length - 8} paradas más</li>` : ''}</ul>` : '';
      const nota = u.paradas.length || av ? '' : `<p class="nota">${u.estado === 'MANTENIMIENTO'
        ? `Fuera de servicio por mantenimiento preventivo hasta el ${fmtCorta(u.noDisponibleHasta)}.` : 'Sin paradas asignadas.'}</p>`;
      html = `<h4>${u.id} ${chip}</h4>
        <div class="sub">${TIPOS[u.tipo]} · ${u.km} km recorridos</div>
        <dl><dt>Carga</dt><dd class="t-${u.nivel}">${u.carga} / ${u.capacidad} paquetes</dd>
        <dt>Posición</dt><dd>(${u.x.toFixed(1)}, ${u.y.toFixed(1)})</dd>
        <dt>Pedidos asignados</dt><dd>${pedidosU.length ? pedidosU.join(', ') : '—'}</dd>${av ? filasAveria(av) : ''}</dl>
        ${av ? `<p class="estado-av">${textoFaseAveria(av)}</p>` : ''}${paradas}${nota}`;
      // v03: desde el detalle se registra la avería de una unidad operativa (DS GUI, sección 3.4)
      if (puedeAveriarse(u)) { acciones.hidden = false; $('btnAveriarUnidad').dataset.unidad = u.id; }
    } else if (sel.tipo === 'almacen') {
      const a = estado.almacenes.find((x) => x.id === sel.id);
      const aqui = averiadasEn(a);
      html = `<h4>${esc(a.nombre)}</h4>
        <div class="sub">${a.central ? 'Almacén central, permanentemente abastecido' : 'Almacén intermedio, se recarga a 1 000 unidades a las 23:59:59'}</div>
        <dl><dt>Ubicación</dt><dd>(${a.x}, ${a.y})</dd>
        ${a.central ? '<dt>Stock</dt><dd>ilimitado</dd>' : `<dt>Stock</dt><dd>${num(a.stock)} / ${num(a.capacidad)}</dd><dt>Consumido</dt><dd class="t-${a.nivel}">${a.consumoPct.toFixed(1)}%</dd>`}
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
        <dt>Atendido por</dt><dd>${p.unidades.length ? p.unidades.join(', ') : 'sin asignar'}</dd></dl>`;
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
      <dt>Ocurrió</dt><dd>${fmtCorta(av.inicio)} · ${av.origen === 'ARCHIVO' ? 'programada' : 'registrada'}</dd>
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
  function elegirPestana(p) {
    pestana = p;
    for (const [id, vista, nombre] of [['tabPedidos', 'vistaPedidos', 'pedidos'], ['tabAverias', 'vistaAverias', 'averias']]) {
      $(id).classList.toggle('activa', p === nombre);
      $(id).setAttribute('aria-selected', String(p === nombre));
      $(vista).hidden = p !== nombre;
    }
    $('tituloDer').textContent = p === 'pedidos' ? 'Rastreo de entregas' : 'Unidades averiadas';
    actualizarListaAverias();
  }
  $('tabPedidos').onclick = () => elegirPestana('pedidos');
  $('tabAverias').onclick = () => elegirPestana('averias');

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
      ? `Sin averías por ahora.${reglas && estado.escenario !== 'DIA_A_DIA' ? ' Las programadas en datos/averias.aaaamm.txt aparecen aquí al ocurrir.' : ''}` : 'Inicia una simulación para registrar averías.' });

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

  /** Avisos de las averías que ocurren (archivo o en otro dispositivo), de los traslados, trasvases y retornos. */
  function avisosAverias(prev) {
    if (!prev || !prev.averias || !estado.averias || prev.inicio !== estado.inicio || prev.escenario !== estado.escenario) return;
    const antes = new Map(prev.averias.map((a) => [a.id, a]));
    for (const a of [...estado.averias].reverse()) {
      const p = antes.get(a.id);
      if (!p) {
        if (!propias.has(a.id)) {
          avisar(`Avería tipo ${a.tipo} en ${a.unidad}${a.origen === 'ARCHIVO' ? ' (programada)' : ''}: `
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
      return `<b>${esc(a.nombre)}</b><br><span>${a.central ? 'Stock ilimitado' : `Stock ${num(a.stock)} de ${num(a.capacidad)} · ${Math.round(a.consumoPct)}% consumido`}</span>`
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
      else if (o.tipo === 'unidad') alternarUnidad(o.id, false);
      else if (o.tipo === 'pedido' && sel && sel.tipo === 'pedido' && sel.id === o.id) seleccionar(null);
      else { if (o.tipo !== 'pedido') quitarFiltroUnidad(); seleccionar({ tipo: o.tipo, id: o.id }); }
    }
    arrastre = null;
    cv.classList.remove('arrastrando');
  });
  cv.addEventListener('pointerleave', () => { $('tooltip').hidden = true; });
  cv.addEventListener('wheel', (e) => {
    e.preventDefault();
    const r = cv.getBoundingClientRect();
    acercar(e.deltaY < 0 ? 1.2 : 1 / 1.2, e.clientX - r.left, e.clientY - r.top);
  }, { passive: false });
  cv.addEventListener('dblclick', () => { vista.zoom = 1; vista.ox = 0; vista.oy = 0; sucio = true; });
  function acercar(f, px, py) {
    const g = geom();
    const [mx, my] = aMundo(px, py, g);
    vista.zoom = Math.min(14, Math.max(0.8, vista.zoom * f));
    const [nx, ny] = aPantalla(mx, my);
    vista.ox += px - nx; vista.oy += py - ny;
    sucio = true;
  }
  $('zoomMas').onclick = () => acercar(1.3, cv.clientWidth / 2, cv.clientHeight / 2);
  $('zoomMenos').onclick = () => acercar(1 / 1.3, cv.clientWidth / 2, cv.clientHeight / 2);
  $('zoomReset').onclick = () => { vista.zoom = 1; vista.ox = 0; vista.oy = 0; sucio = true; };
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
      [num(estado.msMaximoPlan) + ' ms', 'Ta máximo'],
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

  fetch('/api/config').then((r) => r.json()).then((c) => {
    if (!$('fecha').value) $('fecha').value = `${c.fechaInicio}T${c.horaInicio || '00:00'}`;
    if (c.averiaTipo1Horas !== undefined) {
      cfgAveria = { tipo1Horas: c.averiaTipo1Horas, enLugarHoras: c.averiaEnLugarHoras, tipo3Dias: c.averiaTipo3Dias,
        tipo3Hora: c.averiaTipo3Hora, trasvaseMin: c.averiaTrasvaseMinutos };
    }
  }).catch(() => { $('fecha').value = '2026-01-01T00:00'; });

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
      }
      recibidoEn = ahora;
      $('estado').classList.remove('desconectado');
      actualizarPaneles();
      actualizarLista();
      actualizarListaAverias();
      actualizarDialogoAveria();
      mostrarDetalle();
      revisarEventos(prev);
      sucio = true;
    };
    es.onerror = () => {
      const chip = $('estado');
      chip.className = 'estado desconectado';
      chip.querySelector('span').textContent = 'Sin conexión';
    };
  }

  // ================================================================ bucle de dibujo
  function bucle() {
    const animando = estado && estado.estado === 'EJECUTANDO';
    if (sucio || animando) { dibujar(); sucio = false; }
    actualizarReloj();
    requestAnimationFrame(bucle);
  }

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
