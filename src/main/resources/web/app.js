// RUTAPAQ v02 – visualizador del Simulador de rutas (escenario de 5 días).
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
  const ESTADOS_U = { EN_RUTA: 'En ruta', ENTREGANDO: 'Entregando', REFRIGERIO: 'Refrigerio', DISPONIBLE: 'Sin tareas', MANTENIMIENTO: 'En mantenimiento' };
  const ESTADOS_SIM = { SIN_SIMULACION: 'Sin simulación', PREPARADA: 'Preparando', EJECUTANDO: 'Simulando', PAUSADA: 'Detenido', TERMINADA: 'Finalizado' };

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
    if (sel.tipo === 'unidad') return new Set([sel.id]);
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
      return { unidades: new Set([u.id]), pedidos: new Set(u.paradas.filter((p) => p.tipo === 'ENTREGA').map((p) => p.ref)) };
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
        const etiqueta = p.tipo === 'RECARGA' ? 'R' : p.tipo === 'REFRIGERIO' ? 'C' : String(++n);
        ctx.fillStyle = p.tipo === 'REFRIGERIO' ? col.violeta : p.tipo === 'RECARGA' ? col.naranja : col.cian;
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
      const k = Math.round(p[0] * 20) + ':' + Math.round(p[1] * 20);
      if (!grupos.has(k)) grupos.set(k, { pos: p, unidades: [] });
      grupos.get(k).unidades.push(u);
    }
    dibujadas = [];
    for (const gr of grupos.values()) {
      const u = gr.unidades.find((x) => resaltadas.has(x.id)) || gr.unidades[0];
      const [px, py] = aPantalla(gr.pos[0], gr.pos[1], g);
      dibujadas.push({ px, py, unidades: gr.unidades });
      const foco = gr.unidades.some((x) => resaltadas.has(x.id));
      const c = u.estado === 'MANTENIMIENTO' ? col.gris : u.estado === 'REFRIGERIO' ? col.violeta : col.cian;
      ctx.fillStyle = hexA(col.fondo, 0.78);
      ctx.beginPath(); ctx.arc(px, py, foco ? 14 : 11.5, 0, 7); ctx.fill();
      if (foco) { ctx.strokeStyle = col.cian; ctx.lineWidth = 2; ctx.stroke(); }
      if (u.estado === 'ENTREGANDO') {     // anillo punteado: está entregando al cliente (1 h)
        ctx.setLineDash([2.5, 3]); ctx.strokeStyle = col.cian; ctx.lineWidth = 1.4;
        ctx.beginPath(); ctx.arc(px, py, foco ? 18 : 15, 0, 7); ctx.stroke(); ctx.setLineDash([]);
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
    const e = sinSim ? 'SIN_SIMULACION' : estado.estado;
    const chip = $('estado');
    chip.className = 'estado ' + e;
    chip.querySelector('span').textContent = ESTADOS_SIM[e] || e;

    $('btnDetener').disabled = e !== 'EJECUTANDO';
    $('btnDetener').hidden = e === 'PAUSADA';
    $('btnReanudar').hidden = e !== 'PAUSADA';

    const vacio = $('avisoVacio');
    if (sinSim) {
      vacio.hidden = false;
      vacio.innerHTML = '<b>Simulación de 5 días</b><span>Elige la fecha y hora de inicio arriba y presiona <em>Iniciar</em>.</span>';
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
    const caja = $('detalle'), cuerpo = $('detalleCuerpo');
    if (!sel || !estado || !estado.unidades) { caja.hidden = true; return; }
    caja.hidden = false;
    if (sel.tipo === 'unidad') {
      const u = estado.unidades.find((x) => x.id === sel.id);
      if (!u) { caja.hidden = true; return; }
      const entregas = u.paradas.filter((p) => p.tipo === 'ENTREGA');
      const pedidosU = [...new Set(entregas.map((p) => p.ref))];
      let n = 0;
      cuerpo.innerHTML = `<h4>${u.id} <span class="chip ${u.estado}">${ESTADOS_U[u.estado] || u.estado}</span></h4>
        <div class="sub">${TIPOS[u.tipo]} · ${u.km} km recorridos</div>
        <dl><dt>Carga</dt><dd class="t-${u.nivel}">${u.carga} / ${u.capacidad} paquetes</dd>
        <dt>Posición</dt><dd>(${u.x.toFixed(1)}, ${u.y.toFixed(1)})</dd>
        <dt>Pedidos asignados</dt><dd>${pedidosU.length ? pedidosU.join(', ') : '—'}</dd></dl>
        ${u.paradas.length ? `<ul class="paradas">${u.paradas.slice(0, 8).map((p) => `<li><b>${p.tipo === 'ENTREGA' ? ++n : p.tipo === 'RECARGA' ? 'R' : 'C'}</b>${
          p.tipo === 'ENTREGA' ? `Entregar ${p.cantidad} a ${p.ref} en (${p.x}, ${p.y})`
            : p.tipo === 'RECARGA' ? `Recargar en ${nombreAlmacen(p.ref)}` : 'Refrigerio (1 h)'}</li>`).join('')}${u.paradas.length > 8 ? `<li><b></b>y ${u.paradas.length - 8} paradas más</li>` : ''}</ul>`
          : `<p class="nota">${u.estado === 'MANTENIMIENTO' ? 'Fuera de servicio por mantenimiento preventivo durante todo el día.' : 'Sin paradas asignadas.'}</p>`}`;
    } else if (sel.tipo === 'almacen') {
      const a = estado.almacenes.find((x) => x.id === sel.id);
      cuerpo.innerHTML = `<h4>${esc(a.nombre)}</h4>
        <div class="sub">${a.central ? 'Almacén central, permanentemente abastecido' : 'Almacén intermedio, se recarga a 1 000 unidades a las 23:59:59'}</div>
        <dl><dt>Ubicación</dt><dd>(${a.x}, ${a.y})</dd>
        ${a.central ? '<dt>Stock</dt><dd>ilimitado</dd>' : `<dt>Stock</dt><dd>${num(a.stock)} / ${num(a.capacidad)}</dd><dt>Consumido</dt><dd class="t-${a.nivel}">${a.consumoPct.toFixed(1)}%</dd>`}
        <dt>Vienen a recargar</dt><dd>${a.recargan.length ? a.recargan.join(', ') : '—'}</dd></dl>`;
    } else if (sel.tipo === 'pedido') {
      const p = estado.pedidos.find((x) => x.id === sel.id);
      if (!p) { cuerpo.innerHTML = `<h4>${esc(sel.id)} <span class="chip DISPONIBLE">Entregado</span></h4><p class="nota">El pedido ya se entregó por completo y salió de la lista de pedidos en curso.</p>`; return; }
      cuerpo.innerHTML = `<h4>${p.id} ${p.vencido ? '<span class="chip rojo">Vencido</span>' : ''}</h4>
        <div class="sub">Cliente ${esc(p.cliente)} · nodo (${p.x}, ${p.y})</div>
        <dl><dt>Cantidad</dt><dd>${p.cantidad} (entregado ${p.entregado}, en camino ${p.asignado})</dd>
        <dt>Plazo</dt><dd>${p.plazo} h</dd>
        <dt>Registrado</dt><dd>${fmtCorta(p.registro)}</dd>
        <dt>Límite</dt><dd class="t-${p.nivel}">${fmtCorta(p.limite)}</dd>
        <dt>Atendido por</dt><dd>${p.unidades.length ? p.unidades.join(', ') : 'sin asignar'}</dd></dl>`;
    } else if (sel.tipo === 'bloqueo') {
      const b = estado.bloqueos[sel.id];
      if (!b) { caja.hidden = true; sel = null; return; }
      cuerpo.innerHTML = `<h4>Calle bloqueada</h4><div class="sub">No se puede atravesar ni girar en sus nodos</div>
        <dl><dt>Desde</dt><dd>${fmtCorta(b.inicio)}</dd><dt>Hasta</dt><dd>${fmtCorta(b.fin)}</dd>
        <dt>Tramos</dt><dd>${b.vertices.map((v) => `(${v[0]}, ${v[1]})`).join(' → ')}</dd></dl>`;
    }
  }
  function nombreAlmacen(id) { const a = estado.almacenes.find((x) => x.id === id); return a ? a.nombre : id; }
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
      if (o.grupo.length > 1) return `<b>${o.grupo.length} unidades aquí</b><br><span>${o.grupo.map((u) => u.id).join(', ')}</span>`;
      const u = o.grupo[0];
      const prox = u.paradas[0];
      return `<b>${u.id}</b> · ${TIPOS[u.tipo]}<br><span>Carga ${u.carga}/${u.capacidad} (${Math.round(100 * u.carga / u.capacidad)}%) · ${ESTADOS_U[u.estado] || u.estado}</span>`
        + (prox ? `<br><span>Próxima parada: ${prox.tipo === 'ENTREGA' ? 'entrega ' + prox.ref : prox.tipo === 'RECARGA' ? 'recarga en ' + nombreAlmacen(prox.ref) : 'refrigerio'}</span>` : '');
    }
    if (o.tipo === 'almacen') {
      const a = estado.almacenes.find((x) => x.id === o.id);
      return `<b>${esc(a.nombre)}</b><br><span>${a.central ? 'Stock ilimitado' : `Stock ${num(a.stock)} de ${num(a.capacidad)} · ${Math.round(a.consumoPct)}% consumido`}</span>`
        + `<br><span>Vienen a recargar: ${a.recargan.length ? a.recargan.slice(0, 8).join(', ') + (a.recargan.length > 8 ? '…' : '') : 'ninguna'}</span>`;
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
    if (estado.error && estado.error !== errorMostrado) avisar('El planificador falló en este ciclo; las unidades siguen con su plan anterior. ' + estado.error, 'error');
    errorMostrado = estado.error;
    if (estado.estado === 'TERMINADA' && finMostrado !== clave) {
      finMostrado = clave;
      if (estado.motivoFin.startsWith('Fin de la simulación')) mostrarResumenFinal();
      else avisar(estado.motivoFin, 'alerta');
    }
  }

  function mostrarResumenFinal() {
    const r = estado.resumen;
    const ini = fechaLocal(estado.inicio), fin = fechaLocal(estado.hora);
    $('finPeriodo').textContent = `Del ${fmtFecha(ini)} al ${fmtFecha(fin)} · duró ${fmtDur(estado.segundosReales)} en tiempo real · algoritmo ${estado.algoritmo}`;
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
    ];
    $('finGrid').innerHTML = celdas.map(([v, t]) => `<div><b>${v}</b><span>${t}</span></div>`).join('');
    $('modalFin').showModal();
  }
  $('finCerrar').onclick = () => $('modalFin').close();
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
      await post(`/api/simulacion?escenario=SEMANAL&fecha=${encodeURIComponent(v)}`);
      sel = null; anterior = null; anim.clear(); finMostrado = '';
      $('buscar').value = ''; botonLimpiar();
      mostrarDetalle();
      avisar(`Simulación de 5 días iniciada desde el ${fmtFecha(fechaLocal(v))}.`);
    } catch (e) { avisar('No se pudo iniciar: ' + e.message, 'error'); }
  }
  $('btnIniciar').onclick = () => {
    if (estado && (estado.estado === 'EJECUTANDO' || estado.estado === 'PAUSADA')) $('modalConfirmar').showModal();
    else iniciar();
  };
  $('confNo').onclick = () => $('modalConfirmar').close();
  $('confSi').onclick = () => { $('modalConfirmar').close(); iniciar(); };
  $('btnDetener').onclick = () => post('/api/simulacion/detener').catch((e) => avisar(e.message, 'error'));
  $('btnReanudar').onclick = () => post('/api/simulacion/reanudar').catch((e) => avisar(e.message, 'error'));

  fetch('/api/config').then((r) => r.json()).then((c) => {
    if (!$('fecha').value) $('fecha').value = `${c.fechaInicio}T${c.horaInicio || '00:00'}`;
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
      recibidoEn = ahora;
      $('estado').classList.remove('desconectado');
      actualizarPaneles();
      actualizarLista();
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
