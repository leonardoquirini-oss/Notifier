window.VUI = (function () {
    function fetchJson(url, opts) {
        return fetch(url, Object.assign({ credentials: 'same-origin' }, opts || {}))
            .then(function (r) {
                if (!r.ok) {
                    return r.text().then(function (txt) {
                        throw new Error('HTTP ' + r.status + ': ' + txt);
                    });
                }
                return r.json();
            });
    }

    function postJson(url, body) {
        return fetchJson(url, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body || {})
        });
    }

    function deleteUrl(url) {
        return fetchJson(url, { method: 'DELETE' });
    }

    function formatBytes(bytes) {
        if (bytes == null) return '-';
        var sizes = ['B', 'KB', 'MB', 'GB', 'TB'];
        if (bytes === 0) return '0 B';
        var i = Math.floor(Math.log(bytes) / Math.log(1024));
        return (bytes / Math.pow(1024, i)).toFixed(2) + ' ' + sizes[i];
    }

    function formatTs(ms) {
        if (!ms) return '-';
        try {
            return new Date(ms).toISOString().replace('T', ' ').replace('Z', '');
        } catch (e) {
            return '-';
        }
    }

    function escapeHtml(text) {
        if (text == null) return '';
        return String(text)
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#39;');
    }

    function showAlert(message, type) {
        type = type || 'danger';
        var html = '<div class="alert alert-' + type + ' alert-dismissible fade show" role="alert">'
            + escapeHtml(message)
            + '<button type="button" class="btn-close" data-bs-dismiss="alert"></button></div>';
        var holder = document.querySelector('.alert-holder');
        if (!holder) {
            holder = document.createElement('div');
            holder.className = 'alert-holder';
            document.querySelector('main').prepend(holder);
        }
        holder.insertAdjacentHTML('beforeend', html);
    }

    return {
        fetchJson: fetchJson,
        postJson: postJson,
        deleteUrl: deleteUrl,
        formatBytes: formatBytes,
        formatTs: formatTs,
        escapeHtml: escapeHtml,
        showAlert: showAlert
    };
})();
