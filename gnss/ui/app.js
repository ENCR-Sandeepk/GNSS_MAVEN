function setLogType(logType) {
    document.getElementById("logsHeading").textContent = logType;
}

const hourSelect = document.getElementById('hours');
for (let h = 0; h < 24; h++) {
    const option = document.createElement('option');
    option.value = h.toString().padStart(2, '0');
    option.textContent = h.toString().padStart(2, '0');
    hourSelect.appendChild(option);
}

const minuteSelect = document.getElementById('minutes');
for (let m = 0; m < 60; m++) {
    const option = document.createElement('option');
    option.value = m.toString().padStart(2, '0');
    option.textContent = m.toString().padStart(2, '0');
    minuteSelect.appendChild(option);
}

const STORAGE_KEY = 'deviceConfig';
let currentUser = null;

// GNSS Receiver Configuration
let deviceType = null;
let coordinateMode = null;
let lat = null;
let lon = null;
let alt = null;
let average = null;
let resetBaseCoordinates = null;

// GNSS Operating Mode
let gnssMode = null;
let burstWindow = null;
let burstInterval = null;

// Modem settings
let geodetic = null;

// Server Setting
let ip = null;
let port = null;
let mountPoint = null;
let user = null;
let password = null;

// Rover Setting
let rover_id = null;
let scan_start_time = null;
let scanInterval = null;

// Deviation reporting axis (along / transverse)
let axisEnable = null;
let axisAngle = null;
let tempEnable = null;
let structureType = null;
let tempDistance = null;
let tempRefTemp = null;
let tempCoeff = null;
let tempDirection = null;
let tempRefMode = null;        // "manual" | "auto"
let tempBaselineTemp = null;   // recorded by the device when the baseline is established (display only)

// Diagnostics
let debugEnable = null;

// FTP Settings
let ftpEnable = null;
let ftpIP = null;
let ftpPort = null;
let ftpUser = null;
let ftpPass = null;

// Data Storage
let maxRecPerFile = null;
let uploadFileAction = null;
let dateTimeFormat = null;

// Reporting Unit
let deviationUnit = null;
let baseReading = null;
let resetBaseReading = null;

// Elements
const loginPage = document.getElementById('loginPage');
const configPage = document.getElementById('configPage');
const logsPage = document.getElementById('logsPage');
const loginForm = document.getElementById('loginForm');
const mainConfigForm = document.getElementById('mainConfigForm');
const configForm = document.getElementById('configForm');
const roverSettings = document.getElementById('roverSettings');
const baseCoordinatesSection = document.getElementById('baseCoordinatesSection');
const autoFixSection = document.getElementById('autoFixSection');
const manualFeedSection = document.getElementById('manualFeedSection');
const serverSetting = document.getElementById('serverSetting');
const deviceTypeRadios = document.querySelectorAll('input[name="deviceType"]');
const coordinateModeRadios = document.querySelectorAll('input[name="coordinateMode"]');

const DEFAULT_USERNAME = 'admin';
const DEFAULT_PASSWORD = 'admin';

function updateUTCClocks() {
    const now = new Date();
    const utcString =
            now.getUTCHours().toString().padStart(2, '0') + ":" +
            now.getUTCMinutes().toString().padStart(2, '0') + ":" +
            now.getUTCSeconds().toString().padStart(2, '0');

    const clockElement = document.getElementById('utcClock_datalogger');
    if (clockElement) {
        clockElement.textContent = utcString;
    }
}

updateUTCClocks();
setInterval(updateUTCClocks, 1000);

function loadSerialBadge() {
    var badge = document.getElementById('serialBadge');
    if (!badge)
        return;

    fetch('/load_serial')
            .then(function (r) {
                if (r.status === 200) {
                    return r.text().then(function (t) {
                        var sn = t.trim();
                        badge.textContent = 'SN: ' + sn;
                        badge.className = 'serial-badge ok';
                        badge.style.display = 'inline-block';
                    });
                } else if (r.status === 409) {
                    badge.textContent = 'TAMPERED';
                    badge.className = 'serial-badge tampered';
                    badge.style.display = 'inline-block';
                } else {
                    badge.textContent = 'SN: Not Updated';
                    badge.className = 'serial-badge not-set';
                    badge.style.display = 'inline-block';
                }
            })
            .catch(function () {
                badge.textContent = 'SN: Not Updated';
                badge.className = 'serial-badge not-set';
                badge.style.display = 'inline-block';
            });
}

window.addEventListener('load', loadGnssSettings);
window.addEventListener('load', loadSerialBadge);
window.addEventListener('load', loadAppVersion);

function loadAppVersion() {
    fetch('/get_version')
            .then(resp => resp.ok ? resp.text() : Promise.reject())
            .then(ver => {
                const el = document.getElementById('appVersion');
                if (el)
                    el.textContent = 'v' + ver.trim();
            })
            .catch(() => {
            });
}

// Show the axis-angle input + help only when the checkbox is ticked
function updateAxisAngleVisibility() {
    const cb = document.getElementById('axisEnable');
    const sec = document.getElementById('axisAngleSection');
    if (cb && sec)
        sec.style.display = cb.checked ? 'block' : 'none';
}
(function () {
    const cb = document.getElementById('axisEnable');
    if (cb)
        cb.addEventListener('change', updateAxisAngleVisibility);
})();

// Show the temperature-correction fields only when the checkbox is ticked
function updateTempVisibility() {
    const cb = document.getElementById('tempEnable');
    const sec = document.getElementById('tempSection');
    if (cb && sec)
        sec.style.display = cb.checked ? 'block' : 'none';
}
(function () {
    const cb = document.getElementById('tempEnable');
    if (cb)
        cb.addEventListener('change', updateTempVisibility);
})();

// Manual -> show the Reference Temperature box; Automatic -> show the recorded baseline value
function updateTempRefModeVisibility() {
    const sel = document.getElementById('tempRefMode');
    const manual = document.getElementById('tempRefManualGroup');
    const auto = document.getElementById('tempRefAutoGroup');
    if (!sel || !manual || !auto)
        return;
    const isAuto = sel.value === 'auto';
    manual.style.display = isAuto ? 'none' : 'block';
    auto.style.display = isAuto ? 'block' : 'none';
}
(function () {
    const sel = document.getElementById('tempRefMode');
    if (sel)
        sel.addEventListener('change', updateTempRefModeVisibility);
})();

function loadGnssSettings() {
    fetch('/load_gnss_receiver_settings')
            .then(resp => {
                if (!resp.ok)
                    throw new Error('Network response was not ok: ' + resp.status);
                return resp.text();
            })
            .then(txt => {
                if (!txt || txt.trim() === '' || txt.trim() === 'NO_DATA') {
                    console.log('No saved config found (server returned NO_DATA or empty).');
                    return;
                }

                const params = new URLSearchParams(txt.trim());

                deviceType = params.get('deviceType') || null;
                coordinateMode = params.get('coordinateMode') || null;
                lat = params.get('lat') || null;
                lon = params.get('lon') || null;
                alt = params.get('alt') || null;
                average = params.get('average') || null;

                gnssMode = params.get('gnssMode') || null;
                burstWindow = params.get('burstWindow') || null;
                burstInterval = params.get('burstInterval') || null;

                ip = params.get('ip') || null;
                port = params.get('port') || null;
                mountPoint = params.get('mountPoint') || null;
                user = params.get('user') || null;
                password = params.get('password') || null;

                rover_id = params.get('rover_id') || null;
                scan_start_time = params.get('scan_start_time') || null;
                scanInterval = params.get('scanInterval') || null;

                axisEnable = params.get('axisEnable') || null;
                axisAngle = params.get('axisAngle') || null;

                tempEnable = params.get('tempEnable') || null;
                structureType = params.get('structureType') || null;
                tempDistance = params.get('tempDistance') || null;
                tempRefTemp = params.get('tempRefTemp') || null;
                tempCoeff = params.get('tempCoeff') || null;
                tempDirection = params.get('tempDirection') || null;
                tempRefMode = params.get('tempRefMode') || null;
                tempBaselineTemp = params.get('tempBaselineTemp') || null;

                debugEnable = params.get('debugEnable') || null;
                if (debugEnable !== null) {
                    const el = document.getElementById('debugEnable');
                    if (el)
                        el.checked = (debugEnable === "true");
                }

                ftpEnable = params.get('ftpEnable') || null;
                ftpIP = params.get('ftpIP') || null;
                ftpPort = params.get('ftpPort') || null;
                ftpUser = params.get('ftpUser') || null;
                ftpPass = params.get('ftpPass') || null;

                maxRecPerFile = params.get('maxRecPerFile') || null;
                uploadFileAction = params.get('uploadFileAction') || null;
                dateTimeFormat = params.get('dateTimeFormat') || null;

                geodetic = params.get('geodetic') || null;
                deviationUnit = params.get('deviationUnit') || null;
                baseReading = params.get('baseReading') || null;

                if (deviceType) {
                    const el = document.querySelector(`input[name="deviceType"][value="${deviceType}"]`);
                    if (el) {
                        el.checked = true;
                        updateDeviceTypeSections();
                    }
                }

                if (coordinateMode) {
                    const m = document.querySelector(`input[name="coordinateMode"][value="${coordinateMode}"]`);
                    if (m) {
                        m.checked = true;
                        updateCoordinateModeSections();
                    }
                }

                if (gnssMode) {
                    const modeEl = document.querySelector(`input[name="mode"][value="${gnssMode}"]`);
                    if (modeEl) {
                        modeEl.checked = true;
                        updateGnssModeSections();
                    }
                }

                if (burstWindow !== null && document.getElementById('burstWindow'))
                    document.getElementById('burstWindow').value = burstWindow;
                if (burstInterval !== null && document.getElementById('burstInterval'))
                    document.getElementById('burstInterval').value = burstInterval;

                if (average !== null && document.getElementById('averageDelay'))
                    document.getElementById('averageDelay').value = average;

                if (lat !== null && document.getElementById('latitude'))
                    document.getElementById('latitude').value = lat;
                if (lon !== null && document.getElementById('longitude'))
                    document.getElementById('longitude').value = lon;
                if (alt !== null && document.getElementById('altitude'))
                    document.getElementById('altitude').value = alt;

                if (ip !== null && document.getElementById('ipServer'))
                    document.getElementById('ipServer').value = ip;
                if (port !== null && document.getElementById('portServer'))
                    document.getElementById('portServer').value = port;
                if (mountPoint !== null && document.getElementById('mountPoint'))
                    document.getElementById('mountPoint').value = mountPoint;
                if (user !== null && document.getElementById('serverUser'))
                    document.getElementById('serverUser').value = user;
                if (password !== null && document.getElementById('serverPassword'))
                    document.getElementById('serverPassword').value = password;

                if (rover_id !== null && document.getElementById('roverID'))
                    document.getElementById('roverID').value = rover_id;

                if (scan_start_time !== null) {
                    const [hh, mm] = scan_start_time.split(":");
                    if (document.getElementById('hours'))
                        document.getElementById('hours').value = hh;
                    if (document.getElementById('minutes'))
                        document.getElementById('minutes').value = mm;
                }

                if (scanInterval !== null && document.getElementById('scanInterval'))
                    document.getElementById('scanInterval').value = scanInterval;

                if (axisEnable !== null) {
                    const el = document.getElementById('axisEnable');
                    if (el)
                        el.checked = (axisEnable === "true");
                }
                if (axisAngle !== null && document.getElementById('axisAngle'))
                    document.getElementById('axisAngle').value = axisAngle;
                updateAxisAngleVisibility();

                if (tempEnable !== null && document.getElementById('tempEnable'))
                    document.getElementById('tempEnable').checked = (tempEnable === "true");
                if (structureType !== null && document.getElementById('structureType'))
                    document.getElementById('structureType').value = structureType;
                if (tempDistance !== null && document.getElementById('tempDistance'))
                    document.getElementById('tempDistance').value = tempDistance;
                if (tempRefTemp !== null && document.getElementById('tempRefTemp'))
                    document.getElementById('tempRefTemp').value = tempRefTemp;
                if (tempCoeff !== null && document.getElementById('tempCoeff'))
                    document.getElementById('tempCoeff').value = tempCoeff;
                if (tempDirection !== null && tempDirection !== 'null' && document.getElementById('tempDirection'))
                    document.getElementById('tempDirection').value = tempDirection;
                if (document.getElementById('tempRefMode'))
                    document.getElementById('tempRefMode').value = (tempRefMode === 'auto') ? 'auto' : 'manual';
                if (document.getElementById('tempBaselineDisplay')) {
                    const bt = Number(tempBaselineTemp);
                    document.getElementById('tempBaselineDisplay').textContent =
                            (tempBaselineTemp && tempBaselineTemp !== 'null' && !isNaN(bt)) ? (bt.toFixed(2) + ' °C') : 'not recorded yet';
                }
                updateTempVisibility();
                updateTempRefModeVisibility();

                if (ftpEnable !== null) {
                    const el = document.querySelector('#ftpUploadEnable');
                    if (el)
                        el.checked = (ftpEnable === "true");
                }

                if (ftpIP !== null && document.getElementById('ftpIp'))
                    document.getElementById('ftpIp').value = ftpIP;
                if (ftpPort !== null && document.getElementById('ftpPort'))
                    document.getElementById('ftpPort').value = ftpPort;
                if (ftpUser !== null && document.getElementById('ftpUser'))
                    document.getElementById('ftpUser').value = ftpUser;
                if (ftpPass !== null && document.getElementById('ftpPassword'))
                    document.getElementById('ftpPassword').value = ftpPass;

                if (maxRecPerFile !== null && document.getElementById('maxRecordPerFile'))
                    document.getElementById('maxRecordPerFile').value = maxRecPerFile;
                if (uploadFileAction !== null && document.getElementById('uploadFileAction'))
                    document.getElementById('uploadFileAction').value = uploadFileAction;
                if (dateTimeFormat !== null && document.getElementById('dateTimeFormat'))
                    document.getElementById('dateTimeFormat').value = dateTimeFormat;

                if (geodetic !== null) {
                    const el = document.querySelector('#geodetic');
                    if (el)
                        el.checked = (geodetic === "true");
                }

                if (deviationUnit !== null && document.getElementById('deviationUnit'))
                    document.getElementById('deviationUnit').value = deviationUnit;
                if (baseReading !== null && document.getElementById('baseReading'))
                    document.getElementById('baseReading').value = baseReading;

                console.log('Loaded settings from server:', Object.fromEntries(params.entries()));
            })
            .catch(err => {
                console.error('Failed to load saved settings:', err);
            });
}


function isValidDomainOrIP(value) {
    value = value.trim();
    const ipv4Regex = /^(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)){3}$/;
    const ipv6Regex = /^(([0-9a-fA-F]{1,4}):){7}([0-9a-fA-F]{1,4})$/;
    const domainRegex = /^(?!-)(?:[a-zA-Z0-9-]{1,63}\.)+[A-Za-z]{2,}$/;
    return ipv4Regex.test(value) || ipv6Regex.test(value) || domainRegex.test(value);
}


function loadConfiguration() {
    const savedConfig = localStorage.getItem(STORAGE_KEY);
    if (savedConfig) {
        const config = JSON.parse(savedConfig);
        deviceType = config.deviceType;

        const deviceRadio = document.querySelector(`input[name="deviceType"][value="${deviceType}"]`);
        if (deviceRadio)
            deviceRadio.checked = true;

        updateDeviceTypeSections();

        if (deviceType === 'base' && config.coordinateMode) {
            coordinateMode = config.coordinateMode;
            const coordRadio = document.querySelector(`input[name="coordinateMode"][value="${coordinateMode}"]`);
            if (coordRadio)
                coordRadio.checked = true;
            updateCoordinateModeSections();

            if (coordinateMode === 'auto' && config.averageDelay) {
                document.getElementById('averageDelay').value = config.averageDelay;
            }
        }
    } else {
        coordinateMode = null;
    }
}

function updateDeviceTypeSections() {
    if (deviceType === 'rover') {
        roverSettings.classList.remove('hidden');
        serverSetting.classList.remove('hidden');
        baseCoordinatesSection.classList.add('hidden');
    } else if (deviceType === 'base') {
        roverSettings.classList.add('hidden');
        baseCoordinatesSection.classList.remove('hidden');
        serverSetting.classList.remove('hidden');
        if (coordinateMode) {
            updateCoordinateModeSections();
        } else {
            autoFixSection.classList.add('hidden');
            manualFeedSection.classList.add('hidden');
        }
    } else {
        roverSettings.classList.add('hidden');
        baseCoordinatesSection.classList.add('hidden');
        serverSetting.classList.add('hidden');
    }
}

function updateCoordinateModeSections() {
    if (coordinateMode === 'auto') {
        autoFixSection.classList.remove('hidden');
        manualFeedSection.classList.add('hidden');
    } else {
        autoFixSection.classList.add('hidden');
        manualFeedSection.classList.remove('hidden');
    }
}

function showPage(page) {
    loginPage.classList.add('hidden');
    configPage.classList.add('hidden');
    logsPage.classList.add('hidden');

    if (page === 'login') {
        loginPage.classList.remove('hidden');
        document.getElementById('loginError').classList.add('hidden');
    } else if (page === 'config') {
        configPage.classList.remove('hidden');
        loadConfiguration();
    } else if (page === 'receiver_server_logs') {
        logsPage.classList.remove('hidden');
        document.getElementById('usernameDisplayLogs').textContent = currentUser;
        loadLogs("server");
    } else if (page === 'receiver_logs') {
        logsPage.classList.remove('hidden');
        document.getElementById('usernameDisplayLogs').textContent = currentUser;
        loadLogs("log");
    } else if (page === 'download_data') {
        logsPage.classList.remove('hidden');
        document.getElementById('usernameDisplayLogs').textContent = currentUser;
        loadDataFiles();
    }
    window.scrollTo(0, 0);
}


async function loadLogs(type) {
    const logsContainer = document.getElementById('logsContainer');
    logsContainer.innerHTML = "<b>Loading...</b>";

    try {
        const response = await fetch(`/list?type=${type}`);
        const text = await response.text();

        logsContainer.innerHTML = text;

        const links = logsContainer.querySelectorAll("a");
        links.forEach(link => {
            link.addEventListener("click", async function (event) {
                event.preventDefault();
                // Safe: handles filenames that contain "="
                const href = this.getAttribute("href");
                const file = href.slice(href.indexOf("=") + 1);
                loadLogFile(file, type);
            });
        });

    } catch (err) {
        logsContainer.innerHTML = `<span style="color:red">Error loading log list</span>`;
        console.error(err);
    }
}

async function loadLogFile(filename, type) {
    const logsContainer = document.getElementById('logsContainer');
    logsContainer.innerHTML = "<b>No file found...</b>";

    try {
        const response = await fetch(`/view?file=${filename}`);
        const text = await response.text();
        logsContainer.innerHTML = "";

        const fileName = document.createElement("div");
        fileName.className = "log-entry";

        // Use textContent (not innerHTML) to prevent XSS from malicious filenames
        const h2 = document.createElement('h2');
        h2.className = 'label-text';
        h2.textContent = filename;
        fileName.appendChild(h2);
        logsContainer.appendChild(fileName);

        const lines = text.split("\n");
        lines.forEach(line => {
            if (line.trim() === "")
                return;

            const logEntry = document.createElement("div");
            logEntry.className = "log-entry";

            // Use textContent (not innerHTML) to prevent XSS from malicious log content
            const lineSpan = document.createElement('span');
            lineSpan.className = 'log-time';
            lineSpan.textContent = line;
            logEntry.appendChild(lineSpan);

            logsContainer.appendChild(logEntry);
        });

        const logEntry = document.createElement("div");
        logEntry.className = "log-entry";

        const span = document.createElement("span");
        span.className = "log-time";

        const backBtn = document.createElement("button");
        backBtn.className = "btn";
        backBtn.textContent = "Back";
        backBtn.onclick = () => loadLogs(type);

        span.appendChild(backBtn);
        logEntry.appendChild(span);
        logsContainer.appendChild(logEntry);

    } catch (err) {
        logsContainer.innerHTML = `<span style="color:red">Error loading file</span>`;
        console.error(err);
    }
}


loginForm.addEventListener('submit', (e) => {
    e.preventDefault();
    const username = document.getElementById('loginUsername').value.trim();
    const password = document.getElementById('loginPassword').value;

    if (username === DEFAULT_USERNAME && password === DEFAULT_PASSWORD) {
        currentUser = username;
        document.getElementById('usernameDisplay').textContent = username;
        loadGnssSettings();
        showPage('config');
    } else {
        const err = document.getElementById('loginError');
        err.classList.remove('hidden');
        err.textContent = 'Invalid username or password';
    }
});

deviceTypeRadios.forEach(radio => {
    radio.addEventListener('change', (e) => {
        deviceType = e.target.value;
        updateDeviceTypeSections();
    });
});

coordinateModeRadios.forEach(radio => {
    radio.addEventListener('change', (e) => {
        coordinateMode = e.target.value;
        updateCoordinateModeSections();
    });
});

const modeRadios = document.querySelectorAll('input[name="mode"]');
modeRadios.forEach(radio => {
    radio.addEventListener('change', (e) => {
        gnssMode = e.target.value;
        updateGnssModeSections();
    });
});

function updateGnssModeSections() {
    const burstSettings = document.getElementById('burstModeSettings');
    if (gnssMode === 'burst') {
        burstSettings.classList.remove('hidden');
    } else {
        burstSettings.classList.add('hidden');
    }
    // Scan Interval + Scan Start Time apply only in Continuous mode (rover section
    // itself is already hidden for base). In Burst mode the schedule comes from the
    // burst window/interval, so hide them.
    const scanTiming = document.getElementById('scanTimingSection');
    if (scanTiming)
        scanTiming.style.display = (gnssMode === 'burst') ? 'none' : 'block';
}


function saveMainConfiguration() {

    const userPattern = /^[a-zA-Z0-9_]{3,32}$/;
    const mountPattern = /^[A-Za-z][A-Za-z0-9_-]{2,31}$/;

    const deviceTypeChecked = document.querySelector('input[name="deviceType"]:checked');

    if (!deviceTypeChecked) {
        showCustomAlert("Error", 'Please select a Receiver Type before saving.');
        return;
    }

    const selectedDeviceType = deviceTypeChecked.value;
    const config = {deviceType: selectedDeviceType};
    deviceType = selectedDeviceType;

    // Diagnostics (applies to both base and rover)
    const dbgEl = document.getElementById('debugEnable');
    debugEnable = dbgEl ? dbgEl.checked : false;

    const modeChecked = document.querySelector('input[name="mode"]:checked');
    gnssMode = modeChecked ? modeChecked.value : null;

    if (gnssMode === 'burst') {
        burstWindow = document.getElementById('burstWindow').value;
        burstInterval = document.getElementById('burstInterval').value;
    } else {
        burstWindow = null;
        burstInterval = null;
    }

    if (selectedDeviceType === 'base') {
        const coordinateModeChecked = document.querySelector('input[name="coordinateMode"]:checked');
        const mode = coordinateModeChecked ? coordinateModeChecked.value : 'auto';
        config.coordinateMode = mode;
        coordinateMode = mode;

        if (mode === 'auto') {
            const averageDelay = document.getElementById('averageDelay').value;
            if (!averageDelay) {
                showCustomAlert("Error", 'Please select an Average Delay value before saving.');
                return;
            }
            average = averageDelay;
            config.averageDelay = averageDelay;
        } else {
            const latitude = document.getElementById('latitude').value.trim();
            const longitude = document.getElementById('longitude').value.trim();
            const altitude = document.getElementById('altitude').value.trim();

            if (!latitude || !longitude || !altitude) {
                showCustomAlert("Error", 'Please fill in all coordinate fields (Latitude, Longitude, Altitude) before saving.');
                return;
            }
            lat = latitude;
            lon = longitude;
            alt = altitude;
        }
    }

    if (selectedDeviceType === 'rover' || selectedDeviceType === 'base') {

        const ipServer = document.getElementById('ipServer').value.trim();
        const portServer = document.getElementById('portServer').value.trim();
        const mPoint = document.getElementById('mountPoint').value.trim();
        const serverUser = document.getElementById('serverUser').value.trim();
        const serverPassword = document.getElementById('serverPassword').value.trim();

        if (!ipServer || !portServer || !mPoint || !serverUser || !serverPassword) {
            showCustomAlert("Error", 'Please fill in all Server Settings fields before saving.');
            return;
        }

        // Accept both IP addresses and domain names (NTRIP servers commonly use domains)
        if (!isValidDomainOrIP(ipServer)) {
            showCustomAlert("Error", 'Please enter a valid IP address or hostname for Server Settings (e.g., 192.168.1.100 or ntrip.example.com).');
            return;
        }

        const portNum = Number(portServer);
        if (isNaN(portNum) || portNum < 1 || portNum > 65535) {
            showCustomAlert("Error", 'Please enter a valid port number for Server Settings (1–65535).');
            return;
        }

        if (!mountPattern.test(mPoint)) {
            showCustomAlert("Error", 'Mount Point should start with a letter and be 3–32 characters (letters, digits, underscore, hyphen).');
            return;
        }

        // Only enforce minimum length for password (special chars like @ are valid in NTRIP credentials)
        if (serverPassword.length < 3) {
            showCustomAlert("Error", 'Password must be at least 3 characters long for Server Settings.');
            return;
        }

        const resetBase = document.getElementById('resetBaseCoordinates').checked;

        ip = ipServer;
        port = portServer;
        mountPoint = mPoint;
        user = serverUser;
        password = serverPassword;
        resetBaseCoordinates = resetBase;
    }

    if (selectedDeviceType === 'rover') {

        const roverID = document.getElementById('roverID').value.trim();
        if (!roverID) {
            showCustomAlert("Error", 'Please enter Rover ID before saving.');
            return;
        }

        if (!userPattern.test(roverID)) {
            showCustomAlert("Error", 'Rover ID should be 3–32 characters (letters, numbers, underscore only).');
            return;
        }

        rover_id = roverID;

        const hh = document.getElementById('hours').value;
        const mm = document.getElementById('minutes').value;
        const scanInt = document.getElementById('scanInterval').value;

        // Scan timing is only shown/used in Continuous mode; don't block a Burst save on it.
        if (gnssMode !== 'burst') {
            if (!hh) {
                showCustomAlert("Error", 'Please select HH value of Scan Start Time before saving.');
                return;
            }
            if (!mm) {
                showCustomAlert("Error", 'Please select MM value of Scan Start Time before saving.');
                return;
            }
            if (!scanInt) {
                showCustomAlert("Error", 'Please select Scan Interval value before saving.');
                return;
            }
        }
        scan_start_time = `${(hh || "00").toString().padStart(2, "0")}:${(mm || "00").toString().padStart(2, "0")}`;
        scanInterval = scanInt || "1min";

        // Deviation reporting axis (along / transverse)
        const axisEnableChecked = document.getElementById('axisEnable').checked;
        axisEnable = axisEnableChecked;
        if (axisEnableChecked) {
            const axisAngleVal = document.getElementById('axisAngle').value.trim();
            const angleNum = Number(axisAngleVal);
            if (axisAngleVal === "" || isNaN(angleNum) || angleNum < 0 || angleNum > 360) {
                showCustomAlert("Error", 'Please enter a valid Reference Axis Azimuth (0–360 degrees) before saving.');
                return;
            }
            axisAngle = axisAngleVal;
        } else {
            axisAngle = "0";
        }

        // Temperature correction
        tempEnable = document.getElementById('tempEnable').checked;
        structureType = document.getElementById('structureType').value;
        tempRefMode = document.getElementById('tempRefMode').value === 'auto' ? 'auto' : 'manual';
        if (tempEnable) {
            const d = document.getElementById('tempDistance').value.trim();
            const dir = document.getElementById('tempDirection').value.trim();
            const r = document.getElementById('tempRefTemp').value.trim();
            const c = document.getElementById('tempCoeff').value.trim();
            if (d === "" || isNaN(Number(d)) || Number(d) < 0) {
                showCustomAlert("Error", 'Please enter a valid Distance from Fixed Bearing (m).');
                return;
            }
            if (dir === "" || isNaN(Number(dir)) || Number(dir) < 0 || Number(dir) > 360) {
                showCustomAlert("Error", 'Please enter a valid Direction from Fixed Bearing to Rover (0–360 degrees).');
                return;
            }
            if (tempRefMode === 'manual' && (r === "" || isNaN(Number(r)))) {
                showCustomAlert("Error", 'Please enter a valid Reference Temperature (°C), or choose Automatic.');
                return;
            }
            if (c === "" || isNaN(Number(c))) {
                showCustomAlert("Error", 'Please enter a valid Structure Thermal Coefficient (PPM/°C).');
                return;
            }
            tempDistance = d;
            tempDirection = dir;
            tempRefTemp = (r === "" || isNaN(Number(r))) ? (tempRefTemp || "0") : r;
            tempCoeff = c;
        } else {
            tempDistance = tempDistance || "0";
            tempDirection = tempDirection || "0";
            tempRefTemp = tempRefTemp || "0";
            tempCoeff = tempCoeff || "0";
        }

        const ftpUploadEnable = document.getElementById('ftpUploadEnable').checked;
        const ftpIpAddr = document.getElementById('ftpIp').value.trim();
        const ftpPortNum = document.getElementById('ftpPort').value.trim();
        const ftpUsr = document.getElementById('ftpUser').value.trim();
        const ftpPassword = document.getElementById('ftpPassword').value.trim();

        if (!ftpIpAddr || !ftpPortNum || !ftpUsr || !ftpPassword) {
            showCustomAlert("Error", 'Please fill in all FTP Settings fields before saving.');
            return;
        }

        if (!isValidDomainOrIP(ftpIpAddr)) {
            showCustomAlert("Error", 'Please enter a valid host for FTP Settings (e.g., 192.168.1.100 or ftp.example.org).');
            return;
        }

        const portNumber = Number(ftpPortNum);
        if (isNaN(portNumber) || portNumber < 1 || portNumber > 65535) {
            showCustomAlert("Error", 'Please enter a valid port number for FTP Settings (1–65535).');
            return;
        }

        // Only enforce minimum length for FTP password (special chars are valid)
        if (ftpPassword.length < 3) {
            showCustomAlert("Error", 'Password must be at least 3 characters long for FTP Settings.');
            return;
        }

        ftpEnable = ftpUploadEnable;
        ftpIP = ftpIpAddr;
        ftpPort = ftpPortNum;
        ftpUser = ftpUsr;
        ftpPass = ftpPassword;

        const maxRecordPerFile = document.getElementById('maxRecordPerFile').value;
        const uploadFileAct = document.getElementById('uploadFileAction').value;
        const dtFormat = document.getElementById('dateTimeFormat').value;

        if (!maxRecordPerFile || !uploadFileAct || !dtFormat) {
            showCustomAlert("Error", 'Please select all Data Storage options before saving.');
            return;
        }
        maxRecPerFile = maxRecordPerFile;
        uploadFileAction = uploadFileAct;
        dateTimeFormat = dtFormat;

        const geodeticEnable = document.getElementById('geodetic').checked;
        const devUnit = document.getElementById('deviationUnit').value;
        const initialReading = document.getElementById('baseReading').value;

        if (!devUnit || !initialReading) {
            showCustomAlert("Error", 'Please select all Reporting options before saving.');
            return;
        }

        const isChecked = document.getElementById('resetBaseReading').checked;

        geodetic = geodeticEnable;
        deviationUnit = devUnit;
        baseReading = initialReading;
        resetBaseReading = isChecked;
    }

    const data =
            encodeData("gnssMode", gnssMode) + "&" +
            encodeData("burstWindow", burstWindow) + "&" +
            encodeData("burstInterval", burstInterval) + "&" +
            encodeData("deviceType", deviceType) + "&" +
            encodeData("coordinateMode", coordinateMode) + "&" +
            encodeData("lat", lat) + "&" +
            encodeData("lon", lon) + "&" +
            encodeData("alt", alt) + "&" +
            encodeData("average", average) + "&" +
            encodeData("resetBaseCoordinates", resetBaseCoordinates) + "&" +
            encodeData("ip", ip) + "&" +
            encodeData("port", port) + "&" +
            encodeData("mountPoint", mountPoint) + "&" +
            encodeData("user", user) + "&" +
            encodeData("password", password) + "&" +
            encodeData("rover_id", rover_id) + "&" +
            encodeData("scan_start_time", scan_start_time) + "&" +
            encodeData("scanInterval", scanInterval) + "&" +
            encodeData("axisEnable", axisEnable) + "&" +
            encodeData("axisAngle", axisAngle) + "&" +
            encodeData("tempEnable", tempEnable) + "&" +
            encodeData("structureType", structureType) + "&" +
            encodeData("tempDistance", tempDistance) + "&" +
            encodeData("tempRefTemp", tempRefTemp) + "&" +
            encodeData("tempCoeff", tempCoeff) + "&" +
            encodeData("tempDirection", tempDirection) + "&" +
            encodeData("tempRefMode", tempRefMode) + "&" +
            encodeData("tempBaselineTemp", tempBaselineTemp) + "&" +
            encodeData("debugEnable", debugEnable) + "&" +
            encodeData("ftpEnable", ftpEnable) + "&" +
            encodeData("ftpIP", ftpIP) + "&" +
            encodeData("ftpPort", ftpPort) + "&" +
            encodeData("ftpUser", ftpUser) + "&" +
            encodeData("ftpPass", ftpPass) + "&" +
            encodeData("maxRecPerFile", maxRecPerFile) + "&" +
            encodeData("uploadFileAction", uploadFileAction) + "&" +
            encodeData("dateTimeFormat", dateTimeFormat) + "&" +
            encodeData("deviationUnit", deviationUnit) + "&" +
            encodeData("baseReading", baseReading) + "&" +
            encodeData("geodetic", geodetic) + "&" +
            encodeData("resetBaseReading", resetBaseReading);

    fetch('/save_gnss_receiver_settings', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: data
    })
            .then(res => res.text())
            .then(msg => showCustomAlert("Success", msg))
            .catch(err => alert("Error saving GNSS Receiver Settings: " + err));
}

function encodeData(key, value) {
    return encodeURIComponent(key) + "=" + encodeURIComponent(value);
}

document.getElementById('logoutLink').addEventListener('click', () => {
    currentUser = null;
    deviceType = null;
    coordinateMode = null;
    loginForm.reset();
    mainConfigForm.reset();
    configForm.reset();
    showPage('login');
});

document.getElementById('logoutLinkFromLogs').addEventListener('click', () => {
    currentUser = null;
    deviceType = null;
    coordinateMode = null;
    loginForm.reset();
    mainConfigForm.reset();
    configForm.reset();
    showPage('login');
});

document.getElementById('receiver_server_logsLink').addEventListener('click', () => {
    setLogType("Receiver Server Logs");
    showPage('receiver_server_logs');
});

document.getElementById('receiver_logsLink').addEventListener('click', () => {
    setLogType("Receiver Logs");
    showPage('receiver_logs');
});

document.getElementById('downloadDataLink').addEventListener('click', () => {
    setLogType("Download Data");
    showPage('download_data');
});

// ---- Download Data: list CSV files (not-yet-uploaded first, then uploaded) ----
function formatBytes(n) {
    n = Number(n) || 0;
    if (n < 1024)
        return n + " B";
    if (n < 1024 * 1024)
        return (n / 1024).toFixed(1) + " KB";
    return (n / (1024 * 1024)).toFixed(2) + " MB";
}

function dataFileSection(title, subtitle, files, emptyText) {
    const sec = document.createElement('div');
    sec.style.marginBottom = '18px';

    const h = document.createElement('div');
    h.className = 'section-title';
    h.textContent = title + " (" + files.length + ")";
    sec.appendChild(h);

    if (subtitle) {
        const s = document.createElement('div');
        s.style.cssText = 'font-size:12px;color:#6b7684;margin:-4px 0 8px;';
        s.textContent = subtitle;
        sec.appendChild(s);
    }

    if (files.length === 0) {
        const e = document.createElement('div');
        e.style.cssText = 'font-size:13px;color:#8a93a0;padding:6px 0;';
        e.textContent = emptyText;
        sec.appendChild(e);
        return sec;
    }

    const ul = document.createElement('ul');
    ul.style.cssText = 'list-style:none;margin:0;padding:0;';
    files.forEach(f => {
        const li = document.createElement('li');
        li.style.cssText = 'padding:6px 4px;border-bottom:1px solid #eef0f4;display:flex;justify-content:space-between;align-items:center;gap:10px;';

        const a = document.createElement('a');
        // server sends Content-Disposition: attachment -> the browser downloads it
        a.href = '/download?dir=' + encodeURIComponent(f.state) + '&file=' + encodeURIComponent(f.name);
        a.setAttribute('download', f.name);
        a.textContent = f.name;              // textContent: no HTML injection from file names
        a.style.cssText = 'color:#3f4ea8;text-decoration:none;font-family:Consolas,monospace;font-size:13px;word-break:break-all;';
        li.appendChild(a);

        const meta = document.createElement('span');
        meta.style.cssText = 'font-size:12px;color:#8a93a0;white-space:nowrap;';
        meta.textContent = (f.state === 'current' ? 'being recorded · ' : '') + formatBytes(f.size);
        li.appendChild(meta);

        ul.appendChild(li);
    });
    sec.appendChild(ul);
    return sec;
}

async function loadDataFiles() {
    const box = document.getElementById('logsContainer');
    box.innerHTML = "<b>Loading...</b>";
    try {
        const resp = await fetch('/list_data', {cache: 'no-store'});
        if (!resp.ok)
            throw new Error('HTTP ' + resp.status);
        const text = await resp.text();

        const files = text.split('\n').map(l => l.trim()).filter(l => l).map(l => {
            const p = l.split('\t');
            return {state: p[0], name: p[1] || '', size: p[2] || 0};
        }).filter(f => f.name);

        const byNameDesc = (a, b) => (a.name < b.name ? 1 : a.name > b.name ? -1 : 0);   // newest first
        const notUploaded = files.filter(f => f.state === 'current' || f.state === 'pending').sort(byNameDesc);
        const uploaded = files.filter(f => f.state === 'uploaded').sort(byNameDesc);

        box.innerHTML = "";
        const hint = document.createElement('div');
        hint.style.cssText = 'font-size:13px;color:#3a4653;margin-bottom:14px;';
        hint.textContent = 'Click a file name to download it to this computer. Newest files are listed first.';
        box.appendChild(hint);

        box.appendChild(dataFileSection('Not Yet Uploaded',
                'Waiting for FTP upload, or still being recorded.',
                notUploaded, 'No files waiting — everything has been uploaded.'));

        const sep = document.createElement('hr');
        sep.style.cssText = 'border:none;border-top:2px solid #d5daf5;margin:6px 0 18px;';
        box.appendChild(sep);

        const deleteMode = (uploadFileAction === 'delete');
        box.appendChild(dataFileSection('Uploaded',
                'Already sent to the FTP server (kept on the device for 90 days).',
                uploaded,
                deleteMode
                ? 'None kept: Upload File Action is set to "Delete After Upload", so files are removed from the device once uploaded.'
                : 'No uploaded files on the device yet.'));
    } catch (err) {
        box.innerHTML = '<span style="color:red">Error loading data file list</span>';
        console.error(err);
    }
}

document.getElementById('backToConfigLink').addEventListener('click', () => {
    showPage('config');
});

showPage('login');

function showCustomAlert(title, message) {
    document.getElementById("alertTitle").innerText = title;
    document.getElementById("alertMessage").innerText = message;
    document.getElementById("customAlertOverlay").style.display = "flex";
}

function closeCustomAlert() {
    document.getElementById("customAlertOverlay").style.display = "none";
}

function openAxisInfo() {
    document.getElementById("axisInfoOverlay").style.display = "flex";
}

function closeAxisInfo() {
    document.getElementById("axisInfoOverlay").style.display = "none";
}

function openTempInfo() {
    document.getElementById("tempInfoOverlay").style.display = "flex";
}

function closeTempInfo() {
    document.getElementById("tempInfoOverlay").style.display = "none";
}

// ---- IP Settings ----
function loadIpSetting() {
    fetch('/load_ip_setting')
            .then(resp => {
                if (!resp.ok)
                    throw new Error('No IP settings found');
                return resp.text();
            })
            .then(txt => {
                if (!txt || txt.trim() === '' || txt.trim() === 'NO_DATA')
                    return;
                // Guard against HTML response from an outdated server binary
                if (txt.trim().startsWith('<'))
                    return;

                const parts = txt.trim().split(':');
                if (parts.length >= 1) {
                    const savedIp = parts[0];
                    const savedPort = '2000';

                    if (savedIp && document.getElementById('ipSettingAddress'))
                        document.getElementById('ipSettingAddress').value = savedIp;

                    var statusDiv = document.getElementById('ipSettingStatus');
                    statusDiv.style.display = 'block';
                    statusDiv.style.background = '#d4edda';
                    statusDiv.style.color = '#155724';
                    statusDiv.textContent = 'Current Setting: ' + savedIp + ':' + savedPort;
                }
            })
            .catch(function () {
                // No IP settings saved yet — normal on first boot
            });
}

function saveIpSetting() {
    var ipAddr = document.getElementById('ipSettingAddress').value.trim();
    var portNum = '2000';

    if (!ipAddr) {
        showCustomAlert("Error", "Please enter an IP address.");
        return;
    }

    var ipPattern = /^(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d{2}|[1-9]?\d)){3}$/;
    if (!ipPattern.test(ipAddr)) {
        showCustomAlert("Error", "Please enter a valid IP address (e.g., 192.168.1.60).");
        return;
    }

    var data = encodeData('ip', ipAddr) + '&' + encodeData('port', portNum);

    fetch('/save_ip_setting', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: data
    })
            .then(function (res) {
                return res.text();
            })
            .then(function (msg) {
                showCustomAlert("Success", "IP settings saved successfully!");
                var statusDiv = document.getElementById('ipSettingStatus');
                statusDiv.style.display = 'block';
                statusDiv.style.background = '#d4edda';
                statusDiv.style.color = '#155724';
                statusDiv.textContent = 'Current Setting: ' + ipAddr + ':' + portNum;
            })
            .catch(function (err) {
                showCustomAlert("Error", "Failed to save IP settings: " + err);
            });
}

window.addEventListener('load', loadIpSetting);
