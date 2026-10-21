(function () {
  'use strict';

  var PAGE_SIZE = 1000;
  var TEXT_PREVIEW_BYTES = 256 * 1024;
  var TEXT_EXT = ['txt', 'log', 'md', 'csv', 'tsv', 'json', 'jsonl', 'ndjson', 'xml', 'yaml', 'yml', 'toml', 'ini',
    'conf', 'cfg', 'properties', 'env', 'sh', 'bash', 'bat', 'ps1', 'py', 'java', 'kt', 'js', 'mjs', 'ts', 'tsx',
    'jsx', 'html', 'htm', 'css', 'scss', 'sql', 'go', 'rs', 'c', 'h', 'cpp', 'hpp', 'cs', 'rb', 'php', 'pl', 'r',
    'gradle', 'tf', 'dockerfile', 'gitignore', 'svg', 'out', 'err'];
  var IMAGE_EXT = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'svg', 'ico', 'avif'];
  var VIDEO_EXT = ['mp4', 'm4v', 'webm', 'mov'];
  var AUDIO_EXT = ['mp3', 'wav', 'ogg', 'm4a', 'flac'];

  var state = {
    buckets: [],
    bucket: null,
    prefix: '',
    search: '',
    folders: [],
    objects: [],
    nextToken: null,
    truncated: false,
    loading: false,
    seq: 0,
    sort: { field: 'name', dir: 1 },
    selected: null
  };

  var $ = function (id) { return document.getElementById(id); };

  // ---------- 유틸 ----------

  function el(tag, attrs) {
    var e = document.createElement(tag);
    if (attrs) {
      for (var k in attrs) {
        if (k === 'text') e.textContent = attrs[k];
        else if (k === 'className') e.className = attrs[k];
        else if (k.indexOf('on') === 0) e.addEventListener(k.slice(2), attrs[k]);
        else e.setAttribute(k, attrs[k]);
      }
    }
    for (var i = 2; i < arguments.length; i++) {
      var c = arguments[i];
      if (c == null) continue;
      e.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
    }
    return e;
  }

  function qs(params) {
    var parts = [];
    for (var k in params) {
      if (params[k] != null && params[k] !== '') parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(params[k]));
    }
    return parts.join('&');
  }

  function api(path, params) {
    return fetch('api/' + path + '?' + qs(params || {}), { credentials: 'same-origin' }).then(function (r) {
      return r.json().catch(function () { return { error: 'HTTP ' + r.status, message: r.statusText }; })
        .then(function (body) {
          if (!r.ok || body.error) {
            var err = new Error((body.error || ('HTTP ' + r.status)) + (body.message ? ': ' + body.message : ''));
            err.status = r.status;
            throw err;
          }
          return body;
        });
    });
  }

  function objectUrl(key, inline) {
    return 'api/object?' + qs({ bucket: state.bucket, key: key, inline: inline ? '1' : null });
  }

  function formatSize(n) {
    if (n == null || n < 0) return '';
    if (n < 1024) return n + ' B';
    var units = ['KB', 'MB', 'GB', 'TB', 'PB'];
    var v = n, i = -1;
    do { v /= 1024; i++; } while (v >= 1024 && i < units.length - 1);
    return (v >= 100 ? v.toFixed(0) : v >= 10 ? v.toFixed(1) : v.toFixed(2)) + ' ' + units[i];
  }

  function pad(n) { return n < 10 ? '0' + n : '' + n; }

  function formatDate(s) {
    if (!s) return '';
    var d = new Date(s);
    if (isNaN(d.getTime())) return s;
    return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) + ' ' +
      pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
  }

  function extOf(name) {
    var i = name.lastIndexOf('.');
    return i < 0 ? name.toLowerCase() : name.slice(i + 1).toLowerCase();
  }

  function baseName(key) {
    var k = key.charAt(key.length - 1) === '/' ? key.slice(0, -1) : key;
    return k.slice(k.lastIndexOf('/') + 1);
  }

  function showMessage(text) {
    var m = $('message');
    m.textContent = text || '';
    m.hidden = !text;
  }

  function copy(text) {
    if (navigator.clipboard && window.isSecureContext) {
      navigator.clipboard.writeText(text);
    } else {
      var ta = el('textarea', { className: 'offscreen' });
      ta.value = text;
      document.body.appendChild(ta);
      ta.select();
      try { document.execCommand('copy'); } catch (e) { /* 무시 */ }
      document.body.removeChild(ta);
    }
  }

  // ---------- 라우팅 (#b=버킷&p=접두사) ----------

  function readHash() {
    var h = location.hash.replace(/^#/, '');
    var out = { b: null, p: '' };
    h.split('&').forEach(function (pair) {
      var i = pair.indexOf('=');
      if (i < 0) return;
      var k = pair.slice(0, i), v = decodeURIComponent(pair.slice(i + 1));
      if (k === 'b') out.b = v;
      if (k === 'p') out.p = v;
    });
    return out;
  }

  function go(bucket, prefix) {
    var h = '#' + qs({ b: bucket, p: prefix || null });
    if (location.hash === h) onRoute(); else location.hash = h;
  }

  function onRoute() {
    var r = readHash();
    if (!r.b) {
      state.bucket = null;
      renderBuckets();
      renderBreadcrumb();
      if (state.buckets.length === 1) go(state.buckets[0].name, '');
      else renderEmptyListing('왼쪽에서 버킷을 선택하세요.');
      return;
    }
    state.bucket = r.b;
    state.prefix = r.p;
    state.search = '';
    $('filter').value = '';
    closeDetail();
    renderBuckets();
    renderBreadcrumb();
    load(false);
    $('sidebar').classList.remove('open');
  }

  // ---------- 버킷 ----------

  function loadBuckets() {
    return api('buckets').then(function (r) {
      state.buckets = r.buckets;
      renderBuckets();
    }).catch(function (e) {
      showMessage('버킷 목록을 불러오지 못했습니다. ' + e.message +
        '\nListBuckets 권한이 없다면 설정에서 s3.buckets 로 버킷을 지정하세요.');
    });
  }

  function renderBuckets() {
    var ul = $('bucketList');
    var f = $('bucketFilter').value.toLowerCase();
    ul.textContent = '';
    state.buckets.forEach(function (b) {
      if (f && b.name.toLowerCase().indexOf(f) < 0) return;
      ul.appendChild(el('li', null, el('a', {
        href: '#' + qs({ b: b.name }),
        className: b.name === state.bucket ? 'active' : '',
        title: b.name + (b.creationDate ? '\n생성: ' + formatDate(b.creationDate) : ''),
        text: b.name
      })));
    });
    if (!ul.firstChild) ul.appendChild(el('li', { className: 'muted no-bucket', text: '버킷 없음' }));
  }

  // ---------- 목록 ----------

  function load(append) {
    if (!state.bucket) return Promise.resolve();
    var seq = append ? state.seq : ++state.seq;
    if (!append) {
      state.folders = [];
      state.objects = [];
      state.nextToken = null;
      state.truncated = false;
    }
    state.loading = true;
    renderFooter();
    showMessage('');
    var listPrefix = state.prefix + state.search;
    return api('list', {
      bucket: state.bucket,
      prefix: listPrefix,
      token: append ? state.nextToken : null,
      max: PAGE_SIZE
    }).then(function (r) {
      if (seq !== state.seq) return;
      r.prefixes.forEach(function (p) { state.folders.push({ key: p, name: baseName(p) }); });
      r.objects.forEach(function (o) {
        if (o.key === listPrefix && o.key.charAt(o.key.length - 1) === '/') return; // 폴더 마커
        o.name = o.key.slice(state.prefix.length);
        state.objects.push(o);
      });
      state.nextToken = r.nextToken;
      state.truncated = r.truncated && !!r.nextToken;
    }).catch(function (e) {
      if (seq !== state.seq) return;
      showMessage(e.message);
    }).then(function () {
      if (seq !== state.seq) return;
      state.loading = false;
      renderListing();
    });
  }

  function loadAll() {
    var seq = state.seq;
    var step = function () {
      if (seq !== state.seq || !state.truncated) return;
      return load(true).then(step);
    };
    return step();
  }

  function sorted(list, isFolder) {
    var f = state.sort.field, d = state.sort.dir;
    return list.slice().sort(function (a, b) {
      var x, y;
      if (f === 'size' && !isFolder) { x = a.size; y = b.size; }
      else if (f === 'lastModified' && !isFolder) { x = a.lastModified || ''; y = b.lastModified || ''; }
      else { return d * a.name.localeCompare(b.name, undefined, { numeric: true }); }
      return d * (x < y ? -1 : x > y ? 1 : 0);
    });
  }

  function renderEmptyListing(text) {
    $('listing').querySelector('tbody').textContent = '';
    var empty = $('empty');
    empty.textContent = text;
    empty.hidden = false;
    $('summary').textContent = '';
    $('more').hidden = true;
    $('all').hidden = true;
  }

  function renderListing() {
    var tbody = $('listing').querySelector('tbody');
    var filter = $('filter').value.toLowerCase();
    // Enter 로 서버 검색한 경우 서버 결과를 그대로 보여준다
    if (state.search && filter === state.search.toLowerCase()) filter = '';
    var match = function (it) { return !filter || it.name.toLowerCase().indexOf(filter) >= 0; };

    var frag = document.createDocumentFragment();
    if (state.prefix && !state.search) {
      frag.appendChild(el('tr', { onclick: function () { go(state.bucket, parentPrefix(state.prefix)); } },
        el('td', { className: 'name' }, el('span', { className: 'ico', text: '↩' }), '..'),
        el('td', { className: 'num' }), el('td', { className: 'date' })));
    }
    var shown = 0;
    sorted(state.folders, true).forEach(function (f) {
      if (!match(f)) return;
      shown++;
      var name = f.key.slice(state.prefix.length);
      frag.appendChild(el('tr', { onclick: function () { go(state.bucket, f.key); }, title: f.key },
        el('td', { className: 'name' }, el('span', { className: 'ico', text: '📁' }), name),
        el('td', { className: 'num muted', text: '—' }), el('td', { className: 'date' })));
    });
    sorted(state.objects, false).forEach(function (o) {
      if (!match(o)) return;
      shown++;
      var tr = el('tr', { title: o.key, className: state.selected === o.key ? 'selected' : '' },
        el('td', { className: 'name' }, el('span', { className: 'ico', text: iconFor(o.name) }), o.name),
        el('td', { className: 'num', text: formatSize(o.size) }),
        el('td', { className: 'date', text: formatDate(o.lastModified) }));
      tr.addEventListener('click', function () { openDetail(o, tr); });
      frag.appendChild(tr);
    });
    tbody.textContent = '';
    tbody.appendChild(frag);

    var empty = $('empty');
    empty.hidden = shown > 0 || state.loading;
    empty.textContent = filter ? '필터와 일치하는 항목이 없습니다.' : '비어 있습니다.';

    document.querySelectorAll('th.sortable').forEach(function (th) {
      th.classList.remove('sorted-asc', 'sorted-desc');
      if (th.getAttribute('data-sort') === state.sort.field) {
        th.classList.add(state.sort.dir > 0 ? 'sorted-asc' : 'sorted-desc');
      }
    });
    renderFooter();
  }

  function renderFooter() {
    var total = state.folders.length + state.objects.length;
    var bytes = state.objects.reduce(function (s, o) { return s + o.size; }, 0);
    $('summary').textContent = state.loading ? '불러오는 중…' :
      (state.bucket ? '폴더 ' + state.folders.length + '개, 파일 ' + state.objects.length + '개 (' + formatSize(bytes) + ')' +
        (state.truncated ? ' — 일부만 표시됨' : '') + (state.search ? ' — 접두사 검색: "' + state.search + '"' : '') : '');
    $('more').hidden = !state.truncated || state.loading;
    $('all').hidden = !state.truncated || state.loading;
    if (!state.loading && total === 0 && state.bucket) $('empty').hidden = false;
  }

  function iconFor(name) {
    var e = extOf(name);
    if (IMAGE_EXT.indexOf(e) >= 0) return '🖼';
    if (VIDEO_EXT.indexOf(e) >= 0) return '🎞';
    if (AUDIO_EXT.indexOf(e) >= 0) return '🎵';
    if (e === 'pdf') return '📕';
    if (['zip', 'gz', 'tgz', 'tar', '7z', 'rar', 'bz2', 'xz', 'zst'].indexOf(e) >= 0) return '📦';
    if (TEXT_EXT.indexOf(e) >= 0) return '📝';
    return '📄';
  }

  function parentPrefix(p) {
    var trimmed = p.slice(0, -1);
    var i = trimmed.lastIndexOf('/');
    return i < 0 ? '' : trimmed.slice(0, i + 1);
  }

  function renderBreadcrumb() {
    var nav = $('breadcrumb');
    nav.textContent = '';
    if (!state.bucket) {
      nav.appendChild(el('span', { className: 'current muted', text: '버킷을 선택하세요' }));
      return;
    }
    var parts = state.prefix.split('/').filter(function (x, i, a) { return i < a.length - 1; });
    var isRoot = parts.length === 0;
    nav.appendChild(isRoot
      ? el('span', { className: 'current', text: state.bucket })
      : el('a', { href: '#' + qs({ b: state.bucket }), text: state.bucket }));
    var acc = '';
    parts.forEach(function (part, i) {
      acc += part + '/';
      nav.appendChild(el('span', { className: 'sep', text: '/' }));
      nav.appendChild(i === parts.length - 1
        ? el('span', { className: 'current', text: part || '(빈 이름)' })
        : el('a', { href: '#' + qs({ b: state.bucket, p: acc }), text: part || '(빈 이름)' }));
    });
  }

  // ---------- 상세 / 미리보기 ----------

  function closeDetail() {
    state.selected = null;
    $('detail').hidden = true;
    $('preview').textContent = '';
    var sel = document.querySelector('tbody tr.selected');
    if (sel) sel.classList.remove('selected');
  }

  function openDetail(o, tr) {
    var prev = document.querySelector('tbody tr.selected');
    if (prev) prev.classList.remove('selected');
    tr.classList.add('selected');
    state.selected = o.key;
    var bucket = state.bucket;

    $('detail').hidden = false;
    $('detailName').textContent = baseName(o.key);
    $('downloadBtn').href = objectUrl(o.key, false);
    $('openBtn').href = objectUrl(o.key, true);
    $('copyKey').onclick = function () { copy(o.key); };
    $('copyUri').onclick = function () { copy('s3://' + bucket + '/' + o.key); };

    var dl = $('detailMeta');
    dl.textContent = '';
    addMeta(dl, '키', o.key);
    addMeta(dl, '크기', formatSize(o.size) + ' (' + o.size.toLocaleString() + ' bytes)');
    addMeta(dl, '수정 시각', formatDate(o.lastModified));
    if (o.storageClass) addMeta(dl, '스토리지 클래스', o.storageClass);
    var preview = $('preview');
    preview.textContent = '';

    api('head', { bucket: bucket, key: o.key }).then(function (h) {
      if (state.selected !== o.key) return;
      addMeta(dl, 'Content-Type', h.contentType || '');
      addMeta(dl, 'ETag', h.etag || '');
      var hk = Object.keys(h.headers).filter(function (k) {
        return ['content-type', 'content-length', 'etag', 'last-modified'].indexOf(k) < 0;
      });
      if (hk.length) {
        dl.appendChild(el('dd', { className: 'section', text: '헤더' }));
        hk.forEach(function (k) { addMeta(dl, k, h.headers[k]); });
      }
      var mk = Object.keys(h.metadata);
      if (mk.length) {
        dl.appendChild(el('dd', { className: 'section', text: '사용자 메타데이터 (x-amz-meta-*)' }));
        mk.forEach(function (k) { addMeta(dl, k, h.metadata[k]); });
      }
      renderPreview(o, h.contentType || '');
    }).catch(function (e) {
      if (state.selected !== o.key) return;
      addMeta(dl, '오류', e.message);
      renderPreview(o, '');
    });
  }

  function addMeta(dl, k, v) {
    dl.appendChild(el('dt', { text: k }));
    dl.appendChild(el('dd', { text: v }));
  }

  function renderPreview(o, contentType) {
    var preview = $('preview');
    var ct = contentType.toLowerCase();
    var e = extOf(o.name);
    var url = objectUrl(o.key, true);
    if (ct.indexOf('image/') === 0 || IMAGE_EXT.indexOf(e) >= 0) {
      preview.appendChild(el('img', { src: url, alt: o.name }));
    } else if (ct.indexOf('video/') === 0 || VIDEO_EXT.indexOf(e) >= 0) {
      preview.appendChild(el('video', { src: url, controls: '', preload: 'metadata' }));
    } else if (ct.indexOf('audio/') === 0 || AUDIO_EXT.indexOf(e) >= 0) {
      preview.appendChild(el('audio', { src: url, controls: '', preload: 'metadata' }));
    } else if (ct.indexOf('application/pdf') === 0 || e === 'pdf') {
      preview.appendChild(el('iframe', { src: url, title: o.name }));
    } else if (isText(ct, e)) {
      previewText(o);
    } else if (o.size > 0) {
      preview.appendChild(el('button', { text: '텍스트로 미리보기', onclick: function () {
        preview.textContent = '';
        previewText(o);
      } }));
    }
  }

  function isText(ct, e) {
    return ct.indexOf('text/') === 0 || /json|xml|javascript|yaml|csv|x-sh|toml/.test(ct) ||
      TEXT_EXT.indexOf(e) >= 0;
  }

  function previewText(o) {
    var preview = $('preview');
    var key = o.key;
    if (o.size === 0) {
      preview.appendChild(el('p', { className: 'note', text: '빈 파일입니다.' }));
      return;
    }
    var note = el('p', { className: 'note', text: '불러오는 중…' });
    preview.appendChild(note);
    fetch(objectUrl(key, true), {
      headers: { Range: 'bytes=0-' + (TEXT_PREVIEW_BYTES - 1) },
      credentials: 'same-origin'
    }).then(function (r) {
      if (!r.ok) throw new Error('HTTP ' + r.status);
      return r.arrayBuffer();
    }).then(function (buf) {
      if (state.selected !== key) return;
      var bytes = new Uint8Array(buf);
      if (bytes.length > TEXT_PREVIEW_BYTES) bytes = bytes.subarray(0, TEXT_PREVIEW_BYTES);
      var text = new TextDecoder('utf-8').decode(bytes);
      if (extOf(o.name) === 'json' && o.size <= TEXT_PREVIEW_BYTES) {
        try { text = JSON.stringify(JSON.parse(text), null, 2); } catch (e) { /* 원본 표시 */ }
      }
      note.textContent = o.size > TEXT_PREVIEW_BYTES
        ? '처음 ' + formatSize(TEXT_PREVIEW_BYTES) + '만 표시합니다. (전체 ' + formatSize(o.size) + ')' : '';
      preview.appendChild(el('pre', { text: text }));
    }).catch(function (e) {
      note.textContent = '미리보기 실패: ' + e.message;
    });
  }

  // ---------- 이벤트 ----------

  function init() {
    window.addEventListener('hashchange', onRoute);
    $('bucketFilter').addEventListener('input', renderBuckets);
    $('reloadBuckets').addEventListener('click', loadBuckets);
    $('refresh').addEventListener('click', function () { load(false); });
    $('more').addEventListener('click', function () { load(true); });
    $('all').addEventListener('click', loadAll);
    $('closeDetail').addEventListener('click', closeDetail);
    $('menuBtn').addEventListener('click', function () { $('sidebar').classList.toggle('open'); });
    $('filter').addEventListener('input', function () {
      if (state.search && $('filter').value === '') {
        state.search = '';
        load(false);
      } else {
        renderListing();
      }
    });
    $('filter').addEventListener('keydown', function (e) {
      if (e.key === 'Enter') {
        state.search = $('filter').value;
        load(false);
      } else if (e.key === 'Escape') {
        $('filter').value = '';
        if (state.search) { state.search = ''; load(false); } else renderListing();
      }
    });
    document.querySelectorAll('th.sortable').forEach(function (th) {
      th.addEventListener('click', function () {
        var f = th.getAttribute('data-sort');
        state.sort = { field: f, dir: state.sort.field === f ? -state.sort.dir : (f === 'name' ? 1 : -1) };
        renderListing();
      });
    });
    document.addEventListener('keydown', function (e) {
      if (e.key === 'Escape' && !$('detail').hidden && document.activeElement !== $('filter')) closeDetail();
    });

    api('info').then(function (i) {
      $('endpoint').textContent = i.endpoint + ' · ' + i.region;
    }).catch(function () { /* 무시 */ });
    loadBuckets().then(onRoute);
  }

  init();
})();
