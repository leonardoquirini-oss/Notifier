(function () {
    var aggregateChart = null;
    var refreshTimer = null;

    function load() {
        loadServerInfo();
        loadStreams();
        loadAggregateChart();
    }

    function loadServerInfo() {
        VUI.fetchJson('/api/server/info').then(function (info) {
            var card = document.getElementById('serverInfoCard');
            var dbHtml = '';
            if (info.dbSizes) {
                Object.keys(info.dbSizes).forEach(function (k) {
                    dbHtml += '<li>db' + k + ': ' + info.dbSizes[k] + ' chiavi</li>';
                });
            }
            card.innerHTML = '<dl class="row mb-0">'
                + row('Versione', info.version)
                + row('Mode', info.mode)
                + row('Role', info.role)
                + row('Uptime', formatUptime(info.uptimeSeconds))
                + row('Clients', info.connectedClients)
                + row('Hits / Misses', (info.keyspaceHits || 0) + ' / ' + (info.keyspaceMisses || 0))
                + row('Evicted keys', info.evictedKeys)
                + '</dl>'
                + (dbHtml ? '<small class="text-muted"><ul class="mb-0">' + dbHtml + '</ul></small>' : '');

            document.getElementById('memoryCard').innerHTML =
                '<div><strong>Used:</strong> ' + (info.usedMemoryHuman || VUI.formatBytes(info.usedMemory)) + '</div>'
                + '<div><strong>Peak:</strong> ' + (info.usedMemoryPeakHuman || VUI.formatBytes(info.usedMemoryPeak)) + '</div>'
                + '<div><strong>RSS:</strong> ' + VUI.formatBytes(info.usedMemoryRss) + '</div>';

            var pingHtml = info.pingLatencyMs != null
                ? '<div class="display-6 text-success">' + info.pingLatencyMs.toFixed(2) + ' ms</div>'
                : '<div class="text-danger">Ping fallito</div>';
            document.getElementById('pingCard').innerHTML = pingHtml;
        }).catch(function (e) {
            document.getElementById('serverInfoCard').innerHTML = '<div class="text-danger">' + VUI.escapeHtml(e.message) + '</div>';
        });
    }

    function loadStreams() {
        VUI.fetchJson('/api/streams').then(function (streams) {
            var byLength = streams.slice().sort(function (a, b) { return b.length - a.length; }).slice(0, 10);
            var byPending = streams.slice().sort(function (a, b) { return b.pendingTotal - a.pendingTotal; }).slice(0, 10);

            renderTopTable('topByLengthTable', byLength, ['length', 'pendingTotal']);
            renderTopTable('topByPendingTable', byPending, ['pendingTotal', 'length']);
        });
    }

    function renderTopTable(tableId, data, cols) {
        var tbody = document.querySelector('#' + tableId + ' tbody');
        if (!data.length) {
            tbody.innerHTML = '<tr><td colspan="3" class="text-muted text-center">Nessuno stream</td></tr>';
            return;
        }
        tbody.innerHTML = data.map(function (s) {
            return '<tr>'
                + '<td><a href="/streams/' + encodeURIComponent(s.key) + '">' + VUI.escapeHtml(s.key) + '</a></td>'
                + '<td>' + s[cols[0]] + '</td>'
                + '<td>' + s[cols[1]] + '</td>'
                + '</tr>';
        }).join('');
    }

    function loadAggregateChart() {
        VUI.fetchJson('/api/streams').then(function (streams) {
            if (!streams.length) {
                return;
            }
            var promises = streams.map(function (s) {
                return VUI.fetchJson('/api/streams/' + encodeURIComponent(s.key) + '/stats?bucket=minute&range=24h')
                    .catch(function () { return []; });
            });
            Promise.all(promises).then(function (results) {
                var aggregate = {};
                results.forEach(function (arr) {
                    arr.forEach(function (b) {
                        aggregate[b.timestampMs] = (aggregate[b.timestampMs] || 0) + b.count;
                    });
                });
                var sortedKeys = Object.keys(aggregate).map(Number).sort(function (a, b) { return a - b; });
                var labels = sortedKeys.map(function (ts) { return new Date(ts).toISOString().substr(11, 5); });
                var data = sortedKeys.map(function (ts) { return aggregate[ts]; });
                drawAggregate(labels, data);
            });
        });
    }

    function drawAggregate(labels, data) {
        var ctx = document.getElementById('aggregateChart');
        if (aggregateChart) aggregateChart.destroy();
        aggregateChart = new Chart(ctx, {
            type: 'bar',
            data: { labels: labels, datasets: [{ label: 'msg/min', data: data, backgroundColor: '#0d6efd' }] },
            options: { plugins: { legend: { display: false } }, scales: { x: { ticks: { maxTicksLimit: 24 } } } }
        });
    }

    function row(label, value) {
        return '<dt class="col-sm-5">' + VUI.escapeHtml(label) + '</dt><dd class="col-sm-7">' + VUI.escapeHtml(value == null ? '-' : value) + '</dd>';
    }

    function formatUptime(seconds) {
        if (!seconds) return '-';
        var d = Math.floor(seconds / 86400);
        var h = Math.floor((seconds % 86400) / 3600);
        var m = Math.floor((seconds % 3600) / 60);
        return d + 'g ' + h + 'h ' + m + 'm';
    }

    function setupAutoRefresh() {
        var checkbox = document.getElementById('autoRefresh');
        function tick() {
            if (checkbox.checked) load();
        }
        refreshTimer = setInterval(tick, 30000);
    }

    document.addEventListener('DOMContentLoaded', function () {
        load();
        document.getElementById('refreshBtn').addEventListener('click', load);
        setupAutoRefresh();
    });
})();
