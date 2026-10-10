// Shared helpers for the workbench pages. Data is always inserted as text (never innerHTML),
// because error messages and stack traces must not be interpreted as HTML.

/** fetch() a JSON API; throws an Error carrying the server's {"error": ...} message on non-2xx. */
async function api(path, options = {}) {
    const response = await fetch(path, options);
    const body = await response.text();
    const data = body ? JSON.parse(body) : null;
    if (!response.ok) {
        const error = new Error((data && data.error) || `${response.status} ${response.statusText}`);
        error.status = response.status;
        throw error;
    }
    return data;
}

/** el("td", {class: "x"}, "text", childNode) -> element; strings become text nodes. */
function el(tag, attributes = {}, ...children) {
    const element = document.createElement(tag);
    for (const [name, value] of Object.entries(attributes)) {
        if (value !== null && value !== undefined && value !== false) {
            element.setAttribute(name, value === true ? "" : value);
        }
    }
    for (const child of children) {
        if (child !== null && child !== undefined) {
            element.append(child); // append() inserts strings as text
        }
    }
    return element;
}

function badge(status) {
    return el("span", {class: `badge badge-${status.toLowerCase()}`}, status);
}

function formatTime(iso) {
    return iso ? new Date(iso).toLocaleString() : "—";
}

function formatDuration(startIso, endIso) {
    if (!startIso || !endIso) {
        return "—";
    }
    const ms = new Date(endIso) - new Date(startIso);
    return ms < 1000 ? `${ms} ms` : `${(ms / 1000).toFixed(1)} s`;
}

function formatPlan(plan) {
    if (!plan) {
        return "none (happy path)";
    }
    return `step ${plan.failStep} fails ${plan.failTimes}×`;
}

/** Replaces the contents of the element with the given id by an error message. */
function showError(id, message) {
    const target = document.getElementById(id);
    target.replaceChildren(el("p", {class: "error"}, message));
}
