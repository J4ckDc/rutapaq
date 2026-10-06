// RUTAPAQ – visualizador. Recibe el estado por SSE (/api/stream) y lo dibuja en un canvas.
// Toda la lógica (simulación, planificación, semáforo) está en el servidor: aquí solo se dibuja.
(() => {
  const ANCHO = 70, ALTO = 50, MARGEN = 24;
  const COLOR = { VERDE: '#2e9e4f', AMBAR: '#e0a100', ROJO: '#d33a2c', NEUTRO: '#c8c8c8' };
  const cv = document.getElementById('mapa');
  const ctx = cv.getContext('2d');
  const $ = (id) => document.getElementById(id);

  let estado = null;
  let seleccion = null;               // {tipo:'unidad'|'pedido', id}
  const vista = { zoom: 1, ox: 0, oy: 0 };

  // ------------------------------------------------------------- coordenadas
  function escalaBase() {
    const w = cv.clientWidth, h = cv.clientHeight;
    return Math.min((w - 2 * MARGEN) / ANCHO, (h - 2 * MARGEN) / ALTO);
  }
  function aPantalla(x, y) {
    const s = escalaBase() * vista.zoom;
    return [MARGEN + x * s + vista.ox, cv.clientHeight - MARGEN - y * s + vista.oy];
  }
  function aMundo(px, py) {
    const s = escalaBase() * vista.zoom;
    return [(px - MARGEN - vista.ox) / s, (cv.clientHeight - MARGEN - py + vista.oy) / s];
  }

  function ajustar() {
    const dpr = window.devicePixelRatio || 1;
    cv.width = cv.clientWidth * dpr;
    cv.height = cv.clientHeight * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    dibujar();
  }

  // ------------------------------------------------------------- dibujo
  function dibujar() {
    const w = cv.clientWidth, h = cv.clientHeight;
    ctx.clearRect(0, 0, w, h);
    const s = escalaBase() * vista.zoom;

    // retícula
    const paso = s >= 8 ? 1 : s >= 3 ? 5 : 10;
    ctx.lineWidth = 1;
    for (let x = 0; x <= ANCHO; x += paso) linea([[x, 0], [x, ALTO]], x % 10 === 0 ? '#e0e0e0' : '#f1f1f1');
    for (let y = 0; y <= ALTO; y += paso) linea([[0, y], [ANCHO, y]], y % 10 === 0 ? '#e0e0e0' : '#f1f1f1');
    ctx.strokeStyle = '#999';
    const [x0, y0] = aPantalla(0, ALTO), [x1, y1] = aPantalla(ANCHO, 0);
    ctx.strokeRect(x0, y0, x1 - x0, y1 - y0);
    ctx.fillStyle = '#999';
    ctx.font = '11px system-ui';
    for (let x = 0; x <= ANCHO; x += 10) { const [px, py] = aPantalla(x, 0); ctx.fillText(x, px - 4, py + 14); }
    for (let y = 10; y <= ALTO; y += 10) { const [px, py] = aPantalla(0, y); ctx.fillText(y, px - 18, py + 4); }

    if (!estado || !estado.unidades) return;

    // bloqueos
    ctx.fillStyle = '#000';
    const b = estado.bloqueos || [];
    const tb = Math.max(3, s * 0.45);
    for (let i = 0; i + 1 < b.length; i += 2) {
      const [px, py] = aPantalla(b[i], b[i + 1]);
      ctx.fillRect(px - tb / 2, py - tb / 2, tb, tb);
    }

    // rutas: tramo actual de todas las unidades; ruta completa de la seleccionada
    for (const u of estado.unidades) {
      if (u.camino.length) linea([[u.x, u.y], ...u.camino], 'rgba(0,0,0,0.18)', 1.2);
    }
    const sel = seleccion && seleccion.tipo === 'unidad' && estado.unidades.find((u) => u.id === seleccion.id);
    if (sel) {
      const pts = [[sel.x, sel.y], ...sel.camino];
      sel.paradas.forEach((p, i) => { if (i > 0) pts.push([p.x, p.y]); });
      linea(pts, '#1f6fb2', 2.2, [6, 4]);
      if (sel.camino.length) linea([[sel.x, sel.y], ...sel.camino], '#1f6fb2', 2.5);
      sel.paradas.forEach((p, i) => {
        const [px, py] = aPantalla(p.x, p.y);
        ctx.fillStyle = '#1f6fb2';
        ctx.beginPath(); ctx.arc(px, py, 9, 0, 7); ctx.fill();
        ctx.fillStyle = '#fff'; ctx.font = 'bold 10px system-ui'; ctx.textAlign = 'center';
        ctx.fillText(p.tipo === 'RECARGA' ? 'R' : i + 1, px, py + 3.5); ctx.textAlign = 'left';
      });
    }

    // pedidos
    const rp = Math.max(3, Math.min(7, s * 0.35));
    for (const p of estado.pedidos) {
      const [px, py] = aPantalla(p.x, p.y);
      ctx.fillStyle = COLOR[p.nivel] || '#888';
      ctx.beginPath(); ctx.arc(px, py, rp, 0, 7); ctx.fill();
      if (seleccion && seleccion.tipo === 'pedido' && seleccion.id === p.id) {
        ctx.strokeStyle = '#000'; ctx.lineWidth = 2; ctx.beginPath(); ctx.arc(px, py, rp + 4, 0, 7); ctx.stroke();
      }
    }

    // almacenes
    for (const a of estado.almacenes) {
      const [px, py] = aPantalla(a.x, a.y);
      ctx.fillStyle = '#fff'; ctx.strokeStyle = '#000'; ctx.lineWidth = 3;
      ctx.fillRect(px - 9, py - 9, 18, 18); ctx.strokeRect(px - 9, py - 9, 18, 18);
      ctx.fillStyle = '#000'; ctx.font = 'bold 11px system-ui';
      const nombre = a.id.replace('ALM-', '');
      ctx.fillText(a.stock == null ? nombre : `${nombre} · ${a.stock}`, px + 13, py + 4);
    }

    // unidades
    for (const u of estado.unidades) glifo(u, u === sel);
  }

  function glifo(u, resaltada) {
    const [px, py] = aPantalla(u.x, u.y);
    const r = resaltada ? 8 : 6;
    ctx.lineWidth = 1.5;
    ctx.strokeStyle = '#000';
    ctx.fillStyle = u.estado === 'DISPONIBLE' ? '#fff' : u.estado === 'ENTREGANDO' ? '#1f6fb2'
      : u.estado === 'REFRIGERIO' ? '#9a9a9a' : '#222';
    ctx.beginPath();
    if (u.tipo === 'AUTO') ctx.rect(px - r, py - r, 2 * r, 2 * r);
    else if (u.tipo === 'MOTO') { ctx.moveTo(px, py - r - 1); ctx.lineTo(px + r, py + r); ctx.lineTo(px - r, py + r); ctx.closePath(); }
    else ctx.arc(px, py, r, 0, 7);
    ctx.fill(); ctx.stroke();
    if (resaltada) {
      ctx.fillStyle = '#000'; ctx.font = 'bold 12px system-ui';
      ctx.fillText(u.id, px + 11, py - 8);
    }
  }

  function linea(pts, color, ancho = 1, guiones = []) {
    ctx.strokeStyle = color; ctx.lineWidth = ancho; ctx.setLineDash(guiones);
    ctx.beginPath();
    pts.forEach(([x, y], i) => { const [px, py] = aPantalla(x, y); i ? ctx.lineTo(px, py) : ctx.moveTo(px, py); });
    ctx.stroke(); ctx.setLineDash([]);
  }

  // ------------------------------------------------------------- panel
  function actualizarPanel() {
    if (!estado || estado.estado === 'SIN_SIMULACION') {
      $('reloj').textContent = '—';
      $('detalleReloj').textContent = 'Sin simulación en curso.';
      $('indicadores').innerHTML = '';
      return;
    }
    $('reloj').textContent = estado.hora.replace('T', '  ');
    const nombres = { SEMANAL: '5 días', COLAPSO: 'hasta el colapso', DIA_A_DIA: 'día a día' };
    let det = `${nombres[estado.escenario]} · ${estado.estado.toLowerCase()} · ${estado.horasTranscurridas.toFixed(1)} h simuladas · ` +
      `ciclo ${estado.ciclos} (${estado.algoritmo}, ${estado.msUltimoPlan} ms)`;
    if (estado.motivoFin) det += ` · ${estado.motivoFin}`;
    if (estado.error) det += ` · ${estado.error}`;
    $('detalleReloj').textContent = det;
    $('indicadores').innerHTML = estado.indicadores
      .map((i) => `<li><i class="c ${i.nivel}"></i>${i.nombre}<b>${i.valor}</b></li>`).join('');
    $('registro').style.display = estado.escenario === 'DIA_A_DIA' ? '' : 'none';
    mostrarSeleccion();
  }

  function mostrarSeleccion() {
    const el = $('seleccion');
    if (!seleccion || !estado || !estado.unidades) return;
    if (seleccion.tipo === 'unidad') {
      const u = estado.unidades.find((x) => x.id === seleccion.id);
      if (!u) return;
      const entregas = u.paradas.filter((p) => p.tipo === 'ENTREGA');
      el.innerHTML = `<b>${u.id}</b> (${u.tipo.toLowerCase()}) · ${u.estado.replace('_', ' ').toLowerCase()}<br>` +
        `Carga ${u.carga} de ${u.capacidad} · ${u.km} km recorridos<br>` +
        (u.paradas.length ? `Próximas paradas: ${u.paradas.slice(0, 6).map((p) =>
          p.tipo === 'RECARGA' ? `recarga ${p.ref}` : `${p.ref} (${p.cantidad})`).join(' → ')}` +
          (u.paradas.length > 6 ? ' …' : '') + `<br>${entregas.length} entregas pendientes` : 'Sin paradas asignadas');
    } else {
      const p = estado.pedidos.find((x) => x.id === seleccion.id);
      el.innerHTML = p ? `<b>${p.id}</b> en (${p.x}, ${p.y})<br>Entregado ${p.entregado} de ${p.cantidad} · plazo ${p.plazo} h<br>` +
        `Límite: ${p.limite.replace('T', ' ')}` : `Pedido ${seleccion.id} completado.`;
    }
  }

  // ------------------------------------------------------------- interacción local
  let arrastre = null;
  cv.addEventListener('mousedown', (e) => { arrastre = { x: e.offsetX, y: e.offsetY, ox: vista.ox, oy: vista.oy, movio: false }; });
  window.addEventListener('mouseup', () => { if (arrastre) cv.style.cursor = 'grab'; });
  cv.addEventListener('mousemove', (e) => {
    if (!arrastre || e.buttons !== 1) return;
    const dx = e.offsetX - arrastre.x, dy = e.offsetY - arrastre.y;
    if (Math.abs(dx) + Math.abs(dy) > 3) arrastre.movio = true;
    vista.ox = arrastre.ox + dx; vista.oy = arrastre.oy + dy;
    cv.style.cursor = 'grabbing';
    dibujar();
  });
  cv.addEventListener('click', (e) => {
    if (arrastre && arrastre.movio) return;
    seleccionar(e.offsetX, e.offsetY);
  });
  cv.addEventListener('wheel', (e) => {
    e.preventDefault();
    const f = e.deltaY < 0 ? 1.2 : 1 / 1.2;
    const nz = Math.min(12, Math.max(1, vista.zoom * f));
    const [mx, my] = aMundo(e.offsetX, e.offsetY);
    vista.zoom = nz;
    const [px, py] = aPantalla(mx, my);
    vista.ox += e.offsetX - px; vista.oy += e.offsetY - py;
    dibujar();
  }, { passive: false });
  cv.addEventListener('dblclick', () => { vista.zoom = 1; vista.ox = 0; vista.oy = 0; dibujar(); });

  function seleccionar(px, py) {
    if (!estado || !estado.unidades) return;
    let mejor = null, d = 14;
    for (const u of estado.unidades) {
      const [x, y] = aPantalla(u.x, u.y);
      const dd = Math.hypot(x - px, y - py);
      if (dd < d) { d = dd; mejor = { tipo: 'unidad', id: u.id }; }
    }
    if (!mejor) {
      for (const p of estado.pedidos) {
        const [x, y] = aPantalla(p.x, p.y);
        const dd = Math.hypot(x - px, y - py);
        if (dd < d) { d = dd; mejor = { tipo: 'pedido', id: p.id }; }
      }
    }
    seleccion = mejor;
    if (!mejor) $('seleccion').textContent = 'Haga clic en una unidad o un pedido del mapa.';
    mostrarSeleccion();
    dibujar();
  }

  // ------------------------------------------------------------- servidor
  async function post(url) {
    const r = await fetch(url, { method: 'POST' });
    const j = await r.json().catch(() => ({}));
    if (!r.ok) throw new Error(j.error || r.statusText);
    return j;
  }

  $('iniciar').onclick = async () => {
    const esc = $('escenario').value;
    const fecha = esc === 'DIA_A_DIA' ? '' : $('fecha').value;
    try {
      await post(`/api/simulacion?escenario=${esc}&fecha=${fecha}`);
      seleccion = null;
      $('mensaje').textContent = '';
    } catch (e) { $('mensaje').textContent = e.message; }
  };
  $('detener').onclick = () => post('/api/simulacion/detener').catch((e) => { $('mensaje').textContent = e.message; });
  $('registrar').onclick = async () => {
    const q = `x=${$('px').value}&y=${$('py').value}&cantidad=${$('pcant').value}&plazo=${$('pplazo').value}`;
    try {
      const r = await post(`/api/pedidos?${q}`);
      $('mensaje').textContent = `Pedido ${r.id} registrado.`;
    } catch (e) { $('mensaje').textContent = e.message; }
  };

  fetch('/api/config').then((r) => r.json()).then((c) => { $('fecha').value = c.fechaInicio; }).catch(() => {});

  function conectar() {
    const es = new EventSource('/api/stream');
    es.onopen = () => $('conexion').classList.add('ok');
    es.onmessage = (m) => { estado = JSON.parse(m.data); actualizarPanel(); dibujar(); };
    es.onerror = () => { $('conexion').classList.remove('ok'); };   // EventSource reintenta solo
  }

  window.addEventListener('resize', ajustar);
  ajustar();
  conectar();
})();
