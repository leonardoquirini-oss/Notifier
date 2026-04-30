(function () {
    var dt = null;

    function load(pattern) {
        var url = '/api/streams' + (pattern ? '?pattern=' + encodeURIComponent(pattern) : '');
        VUI.fetchJson(url).then(function (rows) {
            renderTable(rows);
        }).catch(function (e) {
            VUI.showAlert('Errore caricamento streams: ' + e.message);
        });
    }

    function renderTable(rows) {
        var tbody = document.querySelector('#streamsTable tbody');
        tbody.innerHTML = rows.map(function (s) {
            return '<tr>'
                + '<td><a href="/streams/' + encodeURIComponent(s.key) + '">' + VUI.escapeHtml(s.key) + '</a></td>'
                + '<td>' + s.length + '</td>'
                + '<td>' + VUI.escapeHtml(s.firstId || '-') + '</td>'
                + '<td>' + VUI.escapeHtml(s.lastId || '-') + '</td>'
                + '<td>' + VUI.formatTs(s.lastTimestampMs) + '</td>'
                + '<td>' + s.groupCount + '</td>'
                + '<td>' + s.pendingTotal + '</td>'
                + '<td>' + (s.memoryBytes != null ? s.memoryBytes : '-') + '</td>'
                + '</tr>';
        }).join('');

        if (dt) {
            dt.destroy();
        }
        dt = $('#streamsTable').DataTable({
            pageLength: 25,
            order: [[1, 'desc']]
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        load(null);
        document.getElementById('filterBtn').addEventListener('click', function () {
            var pattern = document.getElementById('patternInput').value.trim();
            load(pattern || null);
        });
    });
})();
