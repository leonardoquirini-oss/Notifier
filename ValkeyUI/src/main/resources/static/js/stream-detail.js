(function () {
    var main = document.querySelector('main');
    var streamKey = main.getAttribute('data-stream-key');
    var readOnly = main.getAttribute('data-read-only') === 'true';

    var bucketChart = null;
    var lengthChart = null;
    var memoryChart = null;
    var oldestLoaded = null;
    var newestLoaded = null;

    function api(path) {
        return '/api/streams/' + encodeURIComponent(streamKey) + path;
    }

    function loadSummary() {
        VUI.fetchJson('/api/streams/' + encodeURIComponent(streamKey)).then(function (info) {
            var s = info.summary || {};
            var html = ''
                + badge('Lunghezza', s.length)
                + badge('First ID', s.firstId || '-')
                + badge('Last ID', s.lastId || '-')
                + badge('Ultimo ts', VUI.formatTs(s.lastTimestampMs))
                + badge('# gruppi', s.groupCount)
                + badge('Pending', s.pendingTotal)
                + badge('Memoria', s.memoryBytes != null ? VUI.formatBytes(s.memoryBytes) : '-');
            document.getElementById('summaryRow').innerHTML = html;
        });
    }

    function badge(label, value) {
        return '<div class="col-auto"><span class="badge bg-light text-dark border">'
            + VUI.escapeHtml(label) + ': <strong>' + VUI.escapeHtml(String(value == null ? '-' : value)) + '</strong></span></div>';
    }

    function loadMessages(direction) {
        var limit = parseInt(document.getElementById('messageLimit').value, 10) || 100;
        var url = api('/messages?limit=' + limit + '&direction=desc');
        if (direction === 'older' && oldestLoaded) {
            url = api('/messages?limit=' + limit + '&direction=desc&to=' + encodeURIComponent(oldestLoaded));
        } else if (direction === 'newer' && newestLoaded) {
            url = api('/messages?limit=' + limit + '&direction=asc&from=' + encodeURIComponent(newestLoaded));
        }
        VUI.fetchJson(url).then(function (rows) {
            if (!rows.length) {
                return;
            }
            if (!direction) {
                document.querySelector('#messagesTable tbody').innerHTML = '';
            }
            var tbody = document.querySelector('#messagesTable tbody');
            rows.forEach(function (m) {
                var fields = JSON.stringify(m.fields, null, 2);
                tbody.insertAdjacentHTML('beforeend',
                    '<tr><td>' + VUI.escapeHtml(m.id) + '</td>'
                    + '<td>' + VUI.formatTs(m.timestampMs) + '</td>'
                    + '<td><pre class="fields-cell mb-0">' + VUI.escapeHtml(fields) + '</pre></td></tr>');
            });
            updateBoundaries(rows);
        });
    }

    function updateBoundaries(rows) {
        var ids = rows.map(function (r) { return r.id; }).sort();
        var first = ids[0];
        var last = ids[ids.length - 1];
        if (!oldestLoaded || compareIds(first, oldestLoaded) < 0) oldestLoaded = first;
        if (!newestLoaded || compareIds(last, newestLoaded) > 0) newestLoaded = last;
    }

    function compareIds(a, b) {
        var pa = a.split('-').map(Number);
        var pb = b.split('-').map(Number);
        if (pa[0] !== pb[0]) return pa[0] - pb[0];
        return (pa[1] || 0) - (pb[1] || 0);
    }

    function loadGroups() {
        VUI.fetchJson(api('/groups')).then(function (groups) {
            if (!groups.length) {
                document.getElementById('groupsContainer').innerHTML = '<div class="text-muted">Nessun consumer group.</div>';
                return;
            }
            var html = groups.map(function (g) {
                var consumers = (g.consumers || []).map(function (c) {
                    var del = readOnly ? '' : '<button class="btn btn-sm btn-outline-danger ms-2 btn-del-consumer" data-group="' + VUI.escapeHtml(g.name) + '" data-consumer="' + VUI.escapeHtml(c.name) + '">Cancella</button>';
                    return '<li><strong>' + VUI.escapeHtml(c.name) + '</strong> '
                        + '<span class="text-muted small">pending=' + c.pending + ', idle=' + c.idleMs + 'ms</span>'
                        + del + '</li>';
                }).join('');
                var actions = readOnly ? '' :
                    '<button class="btn btn-sm btn-outline-warning btn-setid" data-group="' + VUI.escapeHtml(g.name) + '">SetID</button> '
                    + '<button class="btn btn-sm btn-outline-danger btn-destroy-group" data-group="' + VUI.escapeHtml(g.name) + '">Destroy</button>';
                return '<div class="card mb-2">'
                    + '<div class="card-header d-flex justify-content-between"><strong>' + VUI.escapeHtml(g.name) + '</strong>'
                    + '<span class="text-muted small">last-delivered=' + VUI.escapeHtml(g.lastDeliveredId || '') + ', pending=' + g.pending + ', lag=' + g.lag + '</span></div>'
                    + '<div class="card-body">'
                    + '<ul class="list-unstyled mb-2">' + (consumers || '<li class="text-muted">Nessun consumer</li>') + '</ul>'
                    + actions + '</div></div>';
            }).join('');
            document.getElementById('groupsContainer').innerHTML = html;
            attachGroupHandlers();
        });
    }

    function attachGroupHandlers() {
        document.querySelectorAll('.btn-destroy-group').forEach(function (b) {
            b.addEventListener('click', function () {
                var grp = b.getAttribute('data-group');
                openConfirm('Destroy group "' + grp + '"', grp, function () {
                    return VUI.deleteUrl(api('/groups/' + encodeURIComponent(grp) + '?confirm=' + encodeURIComponent(grp)));
                }, function () { loadGroups(); });
            });
        });
        document.querySelectorAll('.btn-del-consumer').forEach(function (b) {
            b.addEventListener('click', function () {
                var grp = b.getAttribute('data-group');
                var con = b.getAttribute('data-consumer');
                openConfirm('Cancella consumer "' + con + '"', con, function () {
                    return VUI.deleteUrl(api('/groups/' + encodeURIComponent(grp) + '/consumers/' + encodeURIComponent(con)));
                }, function () { loadGroups(); });
            });
        });
        document.querySelectorAll('.btn-setid').forEach(function (b) {
            b.addEventListener('click', function () {
                var grp = b.getAttribute('data-group');
                var id = prompt('Nuovo last-delivered-id per group "' + grp + '" (es. 0, $, <id>):', '0');
                if (id === null) return;
                VUI.postJson(api('/groups/' + encodeURIComponent(grp) + '/setid'), { id: id })
                    .then(loadGroups)
                    .catch(function (e) { VUI.showAlert(e.message); });
            });
        });
    }

    function loadStats() {
        var bucket = document.getElementById('statsBucket').value;
        var range = document.getElementById('statsRange').value;
        VUI.fetchJson(api('/stats?bucket=' + bucket + '&range=' + range)).then(function (data) {
            drawBucket(data);
        });
        VUI.fetchJson(api('/timeseries?metric=length&range=' + range)).then(function (data) {
            drawLine(data, 'lengthChart', 'Lunghezza');
            lengthChartCache = data;
        });
        VUI.fetchJson(api('/timeseries?metric=memory&range=' + range)).then(function (data) {
            drawLine(data, 'memoryChart', 'Memoria (B)');
        });
        VUI.fetchJson(api('/heatmap')).then(drawHeatmap);
    }

    function drawBucket(data) {
        var labels = data.map(function (d) { return new Date(d.timestampMs).toISOString().substr(0, 16).replace('T', ' '); });
        var values = data.map(function (d) { return d.count; });
        if (bucketChart) bucketChart.destroy();
        bucketChart = new Chart(document.getElementById('bucketChart'), {
            type: 'bar',
            data: { labels: labels, datasets: [{ label: 'messaggi', data: values, backgroundColor: '#0d6efd' }] },
            options: { plugins: { legend: { display: false } }, scales: { x: { ticks: { maxTicksLimit: 30 } } } }
        });
    }

    function drawLine(data, canvasId, label) {
        var labels = data.map(function (d) { return new Date(d.timestampMs).toISOString().substr(0, 16).replace('T', ' '); });
        var values = data.map(function (d) { return d.value; });
        var ctx = document.getElementById(canvasId);
        if (canvasId === 'lengthChart' && lengthChart) lengthChart.destroy();
        if (canvasId === 'memoryChart' && memoryChart) memoryChart.destroy();
        var chart = new Chart(ctx, {
            type: 'line',
            data: { labels: labels, datasets: [{ label: label, data: values, borderColor: '#0d6efd', tension: 0.2, pointRadius: 0 }] },
            options: { plugins: { legend: { display: false } }, scales: { x: { ticks: { maxTicksLimit: 24 } } } }
        });
        if (canvasId === 'lengthChart') lengthChart = chart;
        if (canvasId === 'memoryChart') memoryChart = chart;
    }

    function drawHeatmap(grid) {
        var max = 0;
        grid.forEach(function (row) { row.forEach(function (v) { if (v > max) max = v; }); });
        var days = ['Lun', 'Mar', 'Mer', 'Gio', 'Ven', 'Sab', 'Dom'];
        var html = '<div class="heatmap-grid">';
        html += '<div></div>';
        for (var h = 0; h < 24; h++) html += '<div class="text-center small text-muted">' + h + '</div>';
        for (var d = 0; d < 7; d++) {
            html += '<div class="text-end small text-muted">' + days[d] + '</div>';
            for (var hr = 0; hr < 24; hr++) {
                var v = grid[d][hr];
                var intensity = max === 0 ? 0 : Math.min(5, Math.ceil((v / max) * 5));
                html += '<div class="heatmap-cell' + (intensity > 0 ? ' intensity-' + intensity : '') + '" title="' + days[d] + ' ' + hr + ':00 = ' + v + '">' + (v || '') + '</div>';
            }
        }
        html += '</div>';
        document.getElementById('heatmap').innerHTML = html;
    }

    function loadAudit() {
        VUI.fetchJson('/api/audit?limit=200').then(function (rows) {
            var filtered = rows.filter(function (r) { return r.stream === streamKey; });
            var tbody = document.querySelector('#tab-audit table tbody');
            if (!filtered.length) {
                tbody.innerHTML = '<tr><td colspan="6" class="text-muted text-center">Nessuna voce</td></tr>';
                return;
            }
            tbody.innerHTML = filtered.map(function (a) {
                return '<tr>'
                    + '<td>' + VUI.formatTs(a.timestampMs) + '</td>'
                    + '<td>' + VUI.escapeHtml(a.user || '-') + '</td>'
                    + '<td>' + VUI.escapeHtml(a.operation || '-') + '</td>'
                    + '<td>' + VUI.escapeHtml(a.result || '-') + '</td>'
                    + '<td>' + (a.durationMs != null ? a.durationMs : '-') + '</td>'
                    + '<td><code>' + VUI.escapeHtml(a.params || '') + '</code></td>'
                    + '</tr>';
            }).join('');
        });
    }

    function openConfirm(title, expected, action, onSuccess) {
        var modalEl = document.getElementById('confirmModal');
        modalEl.querySelector('.modal-title').textContent = title;
        modalEl.querySelector('.confirm-stream').textContent = expected;
        modalEl.querySelector('.confirm-input').value = '';
        modalEl.querySelector('.confirm-input').placeholder = expected;
        modalEl.querySelector('.confirm-extra').innerHTML = '';
        modalEl.querySelector('.confirm-error').textContent = '';
        var btn = modalEl.querySelector('.confirm-action');
        btn.disabled = true;
        var input = modalEl.querySelector('.confirm-input');
        input.oninput = function () { btn.disabled = input.value !== expected; };
        var modal = new bootstrap.Modal(modalEl);
        btn.onclick = function () {
            btn.disabled = true;
            action().then(function () {
                modal.hide();
                if (onSuccess) onSuccess();
            }).catch(function (e) {
                modalEl.querySelector('.confirm-error').textContent = e.message;
                btn.disabled = false;
            });
        };
        modal.show();
        return modalEl;
    }

    function setupAdminButtons() {
        if (readOnly) return;
        document.getElementById('btnTrim').addEventListener('click', function () {
            var strategy = prompt('Strategia (MAXLEN o MINID):', 'MAXLEN');
            if (!strategy) return;
            var value = prompt('Valore (numero per MAXLEN, id per MINID):', '1000');
            if (!value) return;
            openConfirm('Trim ' + streamKey, streamKey, function () {
                return VUI.postJson(api('/trim'), {
                    strategy: strategy.toUpperCase(),
                    value: value,
                    approximate: true,
                    confirm: streamKey
                });
            }, function () { loadSummary(); loadMessages(); });
        });

        document.getElementById('btnDeleteRange').addEventListener('click', function () {
            var fromId = prompt('From ID (es. 0-0 oppure ISO timestamp):', '0-0');
            if (fromId == null) return;
            var toId = prompt('To ID:', '+');
            if (toId == null) return;
            openConfirm('Delete range su ' + streamKey, streamKey, function () {
                return VUI.postJson(api('/delete-range'), {
                    fromId: fromId,
                    toId: toId,
                    confirm: streamKey
                });
            }, function () { loadSummary(); loadMessages(); });
        });

        document.getElementById('btnDeleteTimeRange').addEventListener('click', function () {
            var fromTime = prompt('From time (ISO-8601 UTC):', '2026-01-01T00:00:00Z');
            if (fromTime == null) return;
            var toTime = prompt('To time (ISO-8601 UTC):', new Date().toISOString());
            if (toTime == null) return;
            openConfirm('Delete time-range su ' + streamKey, streamKey, function () {
                return VUI.postJson(api('/delete-time-range'), {
                    fromTime: fromTime,
                    toTime: toTime,
                    confirm: streamKey
                });
            }, function () { loadSummary(); loadMessages(); });
        });

        document.getElementById('btnDeleteAll').addEventListener('click', function () {
            openConfirm('Delete TOTALE di ' + streamKey, streamKey, function () {
                return VUI.deleteUrl(api('?confirm=' + encodeURIComponent(streamKey)));
            }, function () { window.location.href = '/streams'; });
        });

        var btnCreate = document.getElementById('btnCreateGroup');
        if (btnCreate) {
            btnCreate.addEventListener('click', function () {
                var name = prompt('Nome group:');
                if (!name) return;
                var id = prompt('Start id (0, $, <id>):', '$');
                if (id == null) return;
                VUI.postJson(api('/groups'), { name: name, id: id, mkstream: false })
                    .then(loadGroups)
                    .catch(function (e) { VUI.showAlert(e.message); });
            });
        }
    }

    document.addEventListener('DOMContentLoaded', function () {
        loadSummary();
        loadMessages();
        loadGroups();
        loadStats();
        loadAudit();
        document.getElementById('btnLoadOlder').addEventListener('click', function () { loadMessages('older'); });
        document.getElementById('btnLoadNewer').addEventListener('click', function () { loadMessages('newer'); });
        document.getElementById('btnRefreshStats').addEventListener('click', loadStats);
        setupAdminButtons();
    });
})();
