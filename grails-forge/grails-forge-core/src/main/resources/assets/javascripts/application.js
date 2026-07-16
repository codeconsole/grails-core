// This is a manifest file that'll be compiled into application.js.
//
// Any JavaScript file within this directory can be referenced here using a relative path.
//
// You're free to add application-wide JavaScript to this file, but it's generally better
// to create separate JavaScript files as needed.
//
//= require webjars/jquery/%/dist/jquery.js
//= require webjars/bootstrap/%/dist/js/bootstrap.bundle.js
//= require_self

if (typeof jQuery !== 'undefined') {
    (function($) {
        $('#spinner').ajaxStart(function() {
            $(this).fadeIn();
        }).ajaxStop(function() {
            $(this).fadeOut();
        });
    })(jQuery);
}

// Navbar Controllers filter — rendered only when the list is long enough to warrant
// it (see the threshold in the layout). Filters the menu's [data-name] entries in place.
(function () {
    function applyNavFilter(input) {
        const scope = document.querySelector(input.getAttribute('data-filter-scope') || '');
        if (!scope) return;

        const query = input.value.trim().toLowerCase();
        let visible = 0;
        scope.querySelectorAll('[data-name]').forEach((el) => {
            const show = !query || (el.getAttribute('data-name') || '').toLowerCase().includes(query);
            el.classList.toggle('d-none', !show);
            if (show) visible++;
        });

        const empty = scope.querySelector('.nav-filter-empty');
        if (empty) empty.classList.toggle('d-none', visible > 0);
    }

    document.addEventListener('DOMContentLoaded', () => {
        document.querySelectorAll('.nav-filter-input').forEach((input) => {
            input.addEventListener('input', () => applyNavFilter(input));
            input.addEventListener('search', () => applyNavFilter(input));

            const dropdown = input.closest('.dropdown');
            if (!dropdown) return;
            // Focus the field as the menu opens; clear it once the menu closes.
            dropdown.addEventListener('shown.bs.dropdown', () => input.focus());
            dropdown.addEventListener('hidden.bs.dropdown', () => {
                input.value = '';
                applyNavFilter(input);
            });
        });
    });
})();