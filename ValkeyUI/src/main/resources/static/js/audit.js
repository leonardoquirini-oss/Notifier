(function () {
    function load() {
        var limit = parseInt(document.getElementById('auditLimit').value, 10) || 200;
        VUI.fetchJson('/api/audit?limit=' + limit).then(function (rows) {
            var tbody = document.querySelector('#auditTable tbody');
            if (!rows.length) {
                tbody.innerHTML = '<tr><td colspan="7" class="text-muted text-center">Nessun audit log</td></tr>';
                return;
            }
            tbody.innerHTML = rows.map(function (a) {
                return '<tr>'
                    + '<td>' + VUI.formatTs(a.timestampMs) + '</td>'
                    + '<td>' + VUI.escapeHtml(a.user || '-') + '</td>'
                    + '<td>' + VUI.escapeHtml(a.operation || '-') + '</td>'
                    + '<td><a href="/streams/' + encodeURIComponent(a.stream || '') + '">' + VUI.escapeHtml(a.stream || '') + '</a></td>'
                    + '<td>' + VUI.escapeHtml(a.result || '-') + '</td>'
                    + '<td>' + (a.durationMs != null ? a.durationMs : '-') + '</td>'
                    + '<td><code>' + VUI.escapeHtml(a.params || '') + '</code></td>'
                    + '</tr>';
            }).join('');
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        load();
        document.getElementById('auditRefresh').addEventListener('click', load);
    });
})();
