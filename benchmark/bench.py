#!/usr/bin/env python3
"""Benchmarks Minecraft with and without HMC-Optimizations and hunts for memory leaks.

Run with `python3 -I benchmark/bench.py <command> -h` for details. Commands:
  setup      download HeadlessMc, HMC-Specifics (and optionally Eclipse MAT), install Minecraft
  world      create the template world every run joins
  compare    run the scenarios several times and compare FPS, MSPT and memory
  leak       one long run that looks for memory leaks (class histograms, heap dumps, JFR, MAT)
  render     profile the optimized client with JFR and rank the rendering work that is still left
  analyze    redo the report of a render recording
  summarize  (re)create the summary of a compare result directory

Only the Python 3 standard library is used. Everything is stored in benchmark/work/<loader>-<mc>/.
"""
import argparse
import csv
import json
import os
import queue
import re
import shutil
import signal
import statistics
import subprocess
import sys
import threading
import time
import urllib.request
import zipfile
from pathlib import Path

BENCH_DIR = Path(__file__).resolve().parent
REPO_DIR = BENCH_DIR.parent
WORK_DIR = BENCH_DIR / 'work'
TOOLS_DIR = WORK_DIR / 'tools'

HMC_VERSION = '3.0.0-RC8'
# the Java version HeadlessMc itself needs
HMC_JAVA = 25
HMC_URL = 'https://github.com/headlesshq/headlessmc/releases/download/{version}/headlessmc.jar'
SPECIFICS_VERSION = '3.0.0'
SPECIFICS_URL = 'https://github.com/headlesshq/hmc-specifics/releases/download/{version}/hmc-specifics-{mc}-{version}-{type}-release.jar'
MAT_VERSION = '1.17.0.20260601'
MAT_URL = 'https://download.eclipse.org/mat/{short}/rcp/MemoryAnalyzer-{version}-linux.gtk.x86_64.zip'

WORLD_NAME = 'bench'
LOADER_TYPES = {'fabric': 'fabric', 'forge': 'lexforge', 'neoforge': 'neoforge'}
SCENARIOS = {
    # name: (install hmc-optimizations, run 'optimize off' after joining)
    'vanilla': (False, False),
    'optimized': (True, False),
    'optimize-off': (True, True),
}
OPTIONS = {
    # unlimited fps and no vsync, otherwise both scenarios are capped at the same frame rate
    'maxFps': '260',
    'enableVsync': 'false',
    # since 1.21.2 the client drops to 30 fps after a minute without input, which would hide every difference
    'inactivityFpsLimit': 'minimized',
    'renderDistance': '8',
    'simulationDistance': '8',
    'pauseOnLostFocus': 'false',
    'onboardAccessibility': 'false',
    'tutorialStep': 'none',
    'skipMultiplayerWarning': 'true',
    'joinedFirstServer': 'true',
}
SETUP_COMMANDS = [
    # needs commands, the template world has them enabled
    '/gamemode spectator',
    '/time set 6000',
    '/gamerule doDaylightCycle false',
    '/gamerule doWeatherCycle false',
    '/gamerule doMobSpawning false',
    '/weather clear',
]


def log(*args):
    print(time.strftime('[%H:%M:%S]'), *args, flush=True)


# ----------------------------------------------------------------------------------------------------------------------
# setup

def download(url, target: Path):
    if target.exists() and target.stat().st_size > 0:
        return target
    target.parent.mkdir(parents=True, exist_ok=True)
    log('Downloading', url)
    tmp = target.with_suffix(target.suffix + '.part')
    with urllib.request.urlopen(url, timeout=120) as response, open(tmp, 'wb') as f:
        shutil.copyfileobj(response, f)
    tmp.replace(target)
    return target


def java_feature_version(java_home):
    """Reads the feature version (8, 17, 21, 25, ...) from the release file of a JDK."""
    try:
        text = (Path(java_home) / 'release').read_text()
    except OSError:
        return 0
    m = re.search(r'JAVA_VERSION="(\d+)(?:\.(\d+))?', text)
    if not m:
        return 0
    major = int(m.group(1))
    return int(m.group(2)) if major == 1 and m.group(2) else major


def java_bin(java_home, name='java'):
    path = Path(java_home) / 'bin' / name
    if not path.exists():
        raise SystemExit(f'{path} does not exist, check --java-home')
    return str(path)


class Env:
    """The benchmark directory of one Minecraft version and modloader."""

    def __init__(self, args):
        self.mc = args.mc
        self.loader = args.loader
        self.type = LOADER_TYPES[args.loader]
        self.java_home = os.path.abspath(os.path.expanduser(args.java_home))
        self.hmc_java_home = os.path.abspath(os.path.expanduser(args.hmc_java_home or args.java_home))
        if java_feature_version(self.hmc_java_home) < HMC_JAVA:
            raise SystemExit(f'HeadlessMc {HMC_VERSION} needs Java {HMC_JAVA}+ to run, '
                             f'pass one with --hmc-java-home (Minecraft still runs with --java-home)')
        self.dir = WORK_DIR / f'{self.loader}-{self.mc}'
        self.mc_dir = self.dir / 'mc'
        self.mods_dir = self.dir / 'mods'
        self.template = self.dir / 'template' / WORLD_NAME
        self.hmc_jar = TOOLS_DIR / f'headlessmc-{HMC_VERSION}.jar'
        self.specifics_jar = self.mods_dir / f'hmc-specifics-{self.mc}-{SPECIFICS_VERSION}-{self.type}-release.jar'
        self.optimizations_jar = self.mods_dir / 'hmc-optimizations.jar'

    def hmc_config(self, hmc_dir: Path, game_dir: Path):
        hmc_dir.mkdir(parents=True, exist_ok=True)
        (hmc_dir / 'config.properties').write_text('\n'.join([
            f'hmc.java.versions={self.java_home}',
            f'hmc.files.game={game_dir}',
            'hmc.files.game-for-each-version=false',
            f'hmc.files.mc={self.mc_dir}',
            'hmc.jline.enabled=false',
            'hmc.assets.dummy=true',
            'hmc.log.console-level=INFO',
            '',
        ]))

    def hmc(self, hmc_dir: Path, *args, **kwargs):
        env = dict(os.environ, HMC_FILES_LOCATION=str(hmc_dir))
        return subprocess.run([java_bin(self.hmc_java_home), '-jar', str(self.hmc_jar), *args], env=env, **kwargs)


def find_optimizations_jar(env: Env, build: bool):
    with open(REPO_DIR / 'ci-data.json') as f:
        build_data = json.load(f)['build_data']
    entry = next((e for e in build_data if e.get('mc') == env.mc), None)
    if entry is None:
        raise SystemExit(f'{env.mc} is not in ci-data.json, pass the jar with --jar')
    project = REPO_DIR / entry['dir']
    if build:
        log('Building', project)
        subprocess.run(['./gradlew', 'build', f'-Pminecraft_version={env.mc}', f'-Plexforge_version={entry["lex"]}',
                        f'-Pneoforge_version={entry["neo"]}'], cwd=project, check=True,
                       env=dict(os.environ, JAVA_HOME=env.java_home))
    jars = sorted((project / 'build' / 'libs').glob(f'hmc-optimizations-{env.mc}-*-{env.type}-release.jar'))
    if not jars:
        raise SystemExit(f'No release jar for {env.mc} {env.type} in {project}/build/libs, use --build or --jar')
    return jars[-1]


def cmd_setup(args):
    env = Env(args)
    download(HMC_URL.format(version=HMC_VERSION), env.hmc_jar)
    download(SPECIFICS_URL.format(version=SPECIFICS_VERSION, mc=env.mc, type=env.type), env.specifics_jar)
    jar = Path(args.jar) if args.jar else find_optimizations_jar(env, args.build)
    env.mods_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(jar, env.optimizations_jar)
    log('Using', jar)
    if args.mat:
        short = '.'.join(MAT_VERSION.split('.')[:3])
        archive = download(MAT_URL.format(short=short, version=MAT_VERSION), TOOLS_DIR / f'mat-{MAT_VERSION}.zip')
        target = TOOLS_DIR / 'mat'
        if not (target / 'ParseHeapDump.sh').exists():
            with zipfile.ZipFile(archive) as z:
                z.extractall(TOOLS_DIR)
            for script in ('ParseHeapDump.sh', 'MemoryAnalyzer'):
                p = target / script
                if p.exists():
                    p.chmod(0o755)
        log('Eclipse MAT in', target)

    hmc_dir = env.dir / 'install'
    env.hmc_config(hmc_dir, env.dir / 'install-game')
    log(f'Installing {env.loader} {env.mc}')
    result = env.hmc(hmc_dir, 'version', 'install', env.loader, env.mc)
    if result.returncode != 0:
        raise SystemExit('Failed to install the version')
    log('Setup done, next: world')


# ----------------------------------------------------------------------------------------------------------------------
# running the game

def descendants(pid):
    children = {}
    for stat in Path('/proc').glob('[0-9]*/stat'):
        try:
            text = stat.read_text()
        except OSError:
            continue
        ppid = int(text[text.rindex(')') + 2:].split()[1])
        children.setdefault(ppid, []).append(int(stat.parent.name))
    result, todo = [], [pid]
    while todo:
        for child in children.get(todo.pop(), []):
            result.append(child)
            todo.append(child)
    return result


class Game:
    """A Minecraft client launched by HeadlessMc, controlled through the HMC-Specifics commands on stdin."""

    def __init__(self, env: Env, run_dir: Path, mods, jvm_args, xvfb=False, quick_play=True):
        self.env = env
        self.run_dir = run_dir
        self.game_dir = run_dir / 'game'
        self.lines = queue.Queue()
        self.json_lines = queue.Queue()
        self.pid = None
        (self.game_dir / 'mods').mkdir(parents=True, exist_ok=True)
        for mod in mods:
            shutil.copy2(mod, self.game_dir / 'mods')
        (self.game_dir / 'options.txt').write_text(''.join(f'{k}:{v}\n' for k, v in OPTIONS.items()))
        hmc_dir = run_dir / 'HeadlessMC'
        env.hmc_config(hmc_dir, self.game_dir)
        jvm = ' '.join(['-Dhmc.jline.enabled=false', '-Djava.awt.headless=true', *jvm_args])
        cmd = [java_bin(env.hmc_java_home), '-jar', str(env.hmc_jar), 'launch', '--offline', '--retries', '1', '--jvm', jvm]
        if quick_play:
            cmd += ['--game', f'--quickPlaySingleplayer {WORLD_NAME}']
        if xvfb:
            cmd = ['xvfb-run', '-a', '-s', '-screen 0 1920x1080x24 +extension GLX +render -noreset',
                   cmd[0], '-Dhmc.xvfb.check=true', *cmd[1:]]
        else:
            cmd.insert(cmd.index('--offline'), '-lwjgl')
        cmd += [env.loader, env.mc]
        self.log_file = open(run_dir / 'game.log', 'w')
        self.proc = subprocess.Popen(cmd, cwd=run_dir, env=dict(os.environ, HMC_FILES_LOCATION=str(hmc_dir)),
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, bufsize=1, start_new_session=True)
        threading.Thread(target=self._read, daemon=True).start()

    def _read(self):
        for line in self.proc.stdout:
            self.log_file.write(line)
            self.log_file.flush()
            line = line.rstrip('\n')
            self.lines.put(line)
            start = line.find('{"')
            if start >= 0 and line.endswith('}'):
                self.json_lines.put(line[start:])
        self.lines.put(None)

    def wait_for(self, regex, timeout):
        pattern = re.compile(regex)
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.lines.get(timeout=1)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError(f'The game exited while waiting for {regex}')
            if pattern.search(line):
                return line
        raise RuntimeError(f'Timed out waiting for {regex}')

    def send(self, command):
        self.proc.stdin.write(command + '\n')
        self.proc.stdin.flush()

    def drain(self):
        for q in (self.lines, self.json_lines):
            while not q.empty():
                q.get_nowait()

    def metrics(self, *names, timeout=15):
        self.drain()
        self.send('metrics ' + ' '.join(names) + ' --json')
        end = time.time() + timeout
        while time.time() < end:
            try:
                line = self.json_lines.get(timeout=1)
            except queue.Empty:
                if self.proc.poll() is not None:
                    raise RuntimeError('The game exited')
                continue
            try:
                data = json.loads(line)
            except ValueError:
                continue
            if names[0] in data:
                return data
        return None

    def find_pid(self, timeout=120):
        """The pid of the Minecraft JVM, a descendant of the HeadlessMc process."""
        end = time.time() + timeout
        marker = str(self.game_dir)
        while time.time() < end:
            for pid in descendants(self.proc.pid):
                try:
                    cmdline = Path(f'/proc/{pid}/cmdline').read_bytes().decode(errors='replace')
                except OSError:
                    continue
                argv = cmdline.split('\0')
                # the launcher only knows the game directory from its config, the game gets it as --gameDir
                if marker in cmdline and os.path.basename(argv[0]).startswith('java'):
                    self.pid = pid
                    return pid
            time.sleep(1)
        raise RuntimeError('Could not find the Minecraft process')

    def wait_in_world(self, timeout):
        end = time.time() + timeout
        while time.time() < end:
            data = self.metrics('world.chunks', timeout=10)
            if data and isinstance(data.get('world.chunks'), int) and data['world.chunks'] > 0:
                return
            time.sleep(2)
        raise RuntimeError('Timed out waiting for the world')

    def jcmd(self, *args, timeout=600):
        result = subprocess.run([java_bin(self.env.java_home, 'jcmd'), str(self.pid), *args],
                                capture_output=True, text=True, timeout=timeout)
        return result.stdout

    def quit(self, timeout=120):
        if self.proc.poll() is None:
            try:
                self.send('quit')
                self.proc.wait(timeout=timeout)
            except (OSError, subprocess.TimeoutExpired):
                os.killpg(self.proc.pid, signal.SIGKILL)
                self.proc.wait()
        self.log_file.close()
        return self.proc.returncode


def rss_kb(pid):
    try:
        for line in Path(f'/proc/{pid}/status').read_text().splitlines():
            if line.startswith('VmRSS:'):
                return int(line.split()[1])
    except OSError:
        pass
    return None


NMT_TOTAL = re.compile(r'Total: reserved=(\d+)KB, committed=(\d+)KB')
NMT_CATEGORY = re.compile(r'^-\s+(.+?) \(reserved=(\d+)KB, committed=(\d+)KB\)', re.M)


def parse_nmt(text):
    result = {}
    total = NMT_TOTAL.search(text)
    if total:
        result['total'] = int(total.group(2))
    for name, _, committed in NMT_CATEGORY.findall(text):
        result[name.strip().lower().replace(' ', '_')] = int(committed)
    return result


GC_LINE = re.compile(r'\[(\d+\.\d+)s\].*GC\((\d+)\) (Pause .*?)\s+(\d+)M->(\d+)M\((\d+)M\) ([\d.]+)ms')


def parse_gc_log(path: Path):
    events = []
    if not path.exists():
        return events
    for line in path.read_text(errors='replace').splitlines():
        m = GC_LINE.search(line)
        if m:
            events.append({'uptime': float(m.group(1)), 'id': int(m.group(2)), 'type': m.group(3).strip(),
                           'before_mb': int(m.group(4)), 'after_mb': int(m.group(5)),
                           'committed_mb': int(m.group(6)), 'pause_ms': float(m.group(7))})
    return events


def gc_in_window(run_dir: Path, info):
    """The GC pauses of the gc.log that happened while measuring."""
    lo, hi = info.get('uptime_start') or 0, info.get('uptime_end') or float('inf')
    # jvm.uptime is in whole seconds
    return [e for e in parse_gc_log(run_dir / 'gc.log') if lo <= e['uptime'] < hi + 1]


def prepare_world(game: Game):
    for command in SETUP_COMMANDS:
        game.send(command)
        time.sleep(0.5)


def new_run_dir(base: Path, name):
    run_dir = base / name
    if run_dir.exists():
        shutil.rmtree(run_dir)
    run_dir.mkdir(parents=True)
    return run_dir


def copy_template(env: Env, game_dir: Path):
    if not env.template.exists():
        raise SystemExit(f'No template world in {env.template}, run the world command first')
    saves = game_dir / 'saves'
    saves.mkdir(parents=True, exist_ok=True)
    shutil.copytree(env.template, saves / WORLD_NAME)


def jvm_memory_args(args, run_dir: Path):
    return [f'-Xmx{args.xmx}', '-XX:NativeMemoryTracking=summary',
            f'-Xlog:gc:file={run_dir / "gc.log"}:uptime,level,tags']


# ----------------------------------------------------------------------------------------------------------------------
# readable names

class Remapper:
    """Maps the Fabric intermediary names (net.minecraft.class_310, method_1234) of a production client to Mojang names.

    Fabric clients run with intermediary names, so histograms and profiles would be unreadable otherwise. The mapping is
    built from the intermediary jar Fabric downloaded (official -> intermediary) and the Mojang mappings of the version
    (official -> Mojang). Other loaders already use Mojang class names, they are left alone."""

    def __init__(self, env=None):
        self.classes, self.methods = {}, {}
        if env is not None and env.loader == 'fabric':
            try:
                self._load(env)
            except Exception as e:  # readable names are nice to have, the benchmark works without them
                log('Could not load the Mojang mappings, names stay as they are:', e)

    def _load(self, env):
        cache = env.dir / 'mappings.json'
        if cache.exists():
            data = json.loads(cache.read_text())
            self.classes, self.methods = data['classes'], data['methods']
            return
        version_json = json.loads((env.mc_dir / 'versions' / env.mc / f'{env.mc}.json').read_text())
        mojang = download(version_json['downloads']['client_mappings']['url'], env.dir / 'mojang-mappings.txt')
        jar = env.mc_dir / 'libraries' / 'net' / 'fabricmc' / 'intermediary' / env.mc / f'intermediary-{env.mc}.jar'
        with zipfile.ZipFile(jar) as z:
            tiny = z.read('mappings/mappings.tiny').decode().splitlines()
        # Mojang: "mojang.Name -> obf:" and "    1:2:ret name(args) -> obf"
        obf_methods = {}  # (obf class, obf name, obf desc) -> mojang name
        mojang_class = None
        text = mojang.read_text().splitlines()
        class_obf = {}
        for line in text:
            if line and not line[0].isspace() and not line.startswith('#') and line.endswith(':'):
                name, obf = line[:-1].split(' -> ')
                class_obf[name] = obf
        prim = {'int': 'I', 'long': 'J', 'float': 'F', 'double': 'D', 'boolean': 'Z', 'byte': 'B', 'char': 'C',
                'short': 'S', 'void': 'V'}

        def desc(type_name):
            dims = type_name.count('[]')
            base = type_name.replace('[]', '')
            d = prim.get(base) or 'L' + class_obf.get(base, base).replace('.', '/') + ';'
            return '[' * dims + d

        for line in text:
            if not line or line.startswith('#'):
                continue
            if not line[0].isspace():
                mojang_class = line[:-1].split(' -> ')[0]
                continue
            if '(' not in line:
                continue
            signature, obf = line.strip().split(' -> ')
            signature = re.sub(r'^\d+:\d+:', '', signature)
            ret, rest = signature.split(' ', 1)
            name, args = rest[:-1].split('(')
            full = '(' + ''.join(desc(a) for a in args.split(',') if a) + ')' + desc(ret)
            obf_methods[(class_obf[mojang_class], obf, full)] = name
        methods, obf_to_inter = {}, {}
        for line in tiny[1:]:
            parts = line.split('\t')
            if parts[0] == 'CLASS':
                obf_to_inter[parts[1]] = parts[2]
            elif parts[0] == 'METHOD':
                name = obf_methods.get((parts[1], parts[3], parts[2]))
                if name and parts[4] != name:
                    methods[parts[4]] = name
        mojang_by_obf = {obf: name for name, obf in class_obf.items()}
        classes = {inter.replace('/', '.'): mojang_by_obf[obf] for obf, inter in obf_to_inter.items() if obf in mojang_by_obf}
        cache.write_text(json.dumps({'classes': classes, 'methods': methods}))
        self.classes, self.methods = classes, methods

    def cls(self, name):
        if not self.classes:
            return name
        array = ''
        while name.endswith('[]'):
            name, array = name[:-2], array + '[]'
        if name.startswith('[L') and name.endswith(';'):
            name = name[2:-1]
            array = '[]'
        mapped = self.classes.get(name)
        if mapped is None and '$' in name:  # anonymous classes (Foo$1) are not in the mappings
            outer, _, rest = name.partition('$')
            mapped = self.classes.get(outer, outer) + '$' + rest
        return (mapped or name) + array

    def method(self, name):
        return self.methods.get(name, name)


# ----------------------------------------------------------------------------------------------------------------------
# world

def enable_commands(level_dat: Path):
    """Sets allowCommands in the gzipped NBT of a level.dat, so that the setup commands work."""
    import gzip
    data = bytearray(gzip.decompress(level_dat.read_bytes()))
    tag = b'\x01\x00\x0dallowCommands'
    index = data.find(tag)
    if index < 0:
        log('Warning: no allowCommands tag in', level_dat)
        return
    data[index + len(tag)] = 1
    level_dat.write_bytes(gzip.compress(bytes(data)))


def cmd_world(args):
    env = Env(args)
    run_dir = new_run_dir(env.dir / 'world-creation', 'run')
    game = Game(env, run_dir, [env.specifics_jar], [f'-Xmx{args.xmx}'], xvfb=args.xvfb, quick_play=False)
    try:
        game.wait_for('HMC-Specifics initialized!', 600)
        click(game, r'Singleplayer', timeout=300)
        # 1.20+ opens the CreateWorldScreen directly when there are no worlds, older versions show the list first
        if not try_click(game, r'Create New World', attempts=10):
            raise RuntimeError('No Create New World button')
        time.sleep(2)
        # on the CreateWorldScreen the button has the same name
        try_click(game, r'Create New World', attempts=10)
        game.wait_in_world(600)
        log('In world, letting it generate for', args.generate, 'seconds')
        time.sleep(args.generate)
    finally:
        code = game.quit()
        log('Game exited with', code)
    saves = sorted((game.game_dir / 'saves').iterdir(), key=lambda p: p.stat().st_mtime)
    if not saves:
        raise SystemExit('No world was saved')
    if env.template.exists():
        shutil.rmtree(env.template)
    env.template.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(saves[-1], env.template)
    for name in ('session.lock',):
        (env.template / name).unlink(missing_ok=True)
    enable_commands(env.template / 'level.dat')
    log('Template world in', env.template)


GUI_BUTTON = re.compile(r'^\s*(\d+)\s+(.+?)\s{2,}-?\d+\s+-?\d+\s+\d+\s+\d+\s+[01]\s')


def gui_buttons(game: Game):
    game.drain()
    game.send('gui')
    buttons, end = [], time.time() + 3
    while time.time() < end:
        try:
            line = game.lines.get(timeout=0.5)
        except queue.Empty:
            continue
        if line is None:
            raise RuntimeError('The game exited')
        m = GUI_BUTTON.match(line)
        if m:
            buttons.append((m.group(1), m.group(2).strip()))
    return buttons


def try_click(game: Game, regex, attempts=5):
    for _ in range(attempts):
        for button_id, text in gui_buttons(game):
            if re.fullmatch(regex, text):
                log('Clicking', text)
                game.send(f'click {button_id}')
                time.sleep(2)
                return True
        time.sleep(2)
    return False


def click(game: Game, regex, timeout):
    if not try_click(game, regex, attempts=max(1, timeout // 5)):
        raise RuntimeError(f'Button {regex} not found')


# ----------------------------------------------------------------------------------------------------------------------
# compare

SAMPLE_FIELDS = ['t', 'fps', 'mspt_avg', 'mspt_max', 'heap_used_mb', 'heap_total_mb', 'rss_mb', 'chunks', 'entities',
                 'threads', 'nmt_total_mb', 'nmt_java_heap_mb', 'nmt_class_mb', 'nmt_thread_mb', 'nmt_code_mb',
                 'nmt_gc_mb', 'nmt_internal_mb', 'nmt_other_mb', 'nmt_malloc_mb']


def sample(game: Game, with_nmt):
    row = {'t': round(time.time(), 1)}
    data = game.metrics('client.fps', 'client.mspt', 'jvm.memory', 'jvm.threads', 'world.chunks', 'world.entities') or {}
    row['fps'] = data.get('client.fps')
    mspt = data.get('client.mspt') or {}
    row['mspt_avg'] = mspt.get('avg')
    row['mspt_max'] = mspt.get('max')
    memory = data.get('jvm.memory') or {}
    row['heap_used_mb'] = memory.get('used')
    row['heap_total_mb'] = memory.get('total')
    row['chunks'] = data.get('world.chunks')
    row['entities'] = data.get('world.entities')
    row['threads'] = (data.get('jvm.threads') or {}).get('live')
    rss = rss_kb(game.pid)
    row['rss_mb'] = round(rss / 1024, 1) if rss else None
    if with_nmt:
        nmt = parse_nmt(game.jcmd('VM.native_memory', 'summary', timeout=60))
        for key in ('total', 'java_heap', 'class', 'thread', 'code', 'gc', 'internal', 'other', 'malloc'):
            value = nmt.get(key)
            row[f'nmt_{key}_mb'] = round(value / 1024, 1) if value is not None else None
    return row


def run_scenario(env: Env, args, scenario, run_dir: Path):
    install, optimize_off = SCENARIOS[scenario]
    mods = [env.specifics_jar] + ([env.optimizations_jar] if install else [])
    game = Game(env, run_dir, mods, jvm_memory_args(args, run_dir), xvfb=args.xvfb)
    copy_template(env, game.game_dir)
    samples, info = [], {'scenario': scenario, 'mods': [Path(m).name for m in mods]}
    try:
        game.wait_for('HMC-Specifics initialized!', 900)
        game.find_pid()
        game.wait_in_world(600)
        prepare_world(game)
        if optimize_off:
            game.send('optimize off')
        log(f'{scenario}: in world, warming up for {args.warmup}s')
        time.sleep(args.warmup)
        start = time.time()
        info['uptime_start'] = (game.metrics('jvm.uptime') or {}).get('jvm.uptime')
        next_nmt = 0.0
        next_move = start + args.move_every if args.move_every else None
        while time.time() - start < args.duration:
            tick = time.time()
            with_nmt = tick >= next_nmt
            if with_nmt:
                next_nmt = tick + args.nmt_interval
            samples.append(sample(game, with_nmt))
            if next_move and tick >= next_move:
                game.send(f'/tp @s ~{args.move_distance} ~ ~')
                next_move = tick + args.move_every
            time.sleep(max(0.0, args.interval - (time.time() - tick)))
        info['uptime_end'] = (game.metrics('jvm.uptime') or {}).get('jvm.uptime')
        info['nmt_final'] = game.jcmd('VM.native_memory', 'summary', timeout=60)
    finally:
        info['exit_code'] = game.quit()
    with open(run_dir / 'samples.csv', 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=SAMPLE_FIELDS)
        writer.writeheader()
        writer.writerows(samples)
    info['gc'] = gc_in_window(run_dir, info)
    (run_dir / 'run.json').write_text(json.dumps(info, indent=2))
    return samples, info


def cmd_compare(args):
    env = Env(args)
    stamp = time.strftime('%Y%m%d-%H%M%S')
    result_dir = env.dir / 'results' / f'compare-{stamp}'
    result_dir.mkdir(parents=True)
    (result_dir / 'config.json').write_text(json.dumps(vars(args), indent=2, default=str))
    scenarios = args.scenarios.split(',')
    for rep in range(1, args.reps + 1):
        # interleave the scenarios, so that a slow drift of the machine affects all of them alike
        for scenario in scenarios:
            log(f'=== {scenario} repetition {rep}/{args.reps}')
            for attempt in range(1, args.attempts + 1):
                run_dir = new_run_dir(result_dir, f'{scenario}-{rep}')
                try:
                    run_scenario(env, args, scenario, run_dir)
                    break
                except Exception as e:
                    # HeadlessMc needs the network to parse versions at launch, so a failed start is retried
                    log(f'{scenario} repetition {rep} attempt {attempt}/{args.attempts} failed: {e}')
                    (run_dir / 'error.txt').write_text(str(e))
                    time.sleep(15)
            if not args.keep_game:
                shutil.rmtree(run_dir / 'game', ignore_errors=True)
    summary = summarize(result_dir)
    print(summary)


# ----------------------------------------------------------------------------------------------------------------------
# summary

def percentile(values, p):
    values = sorted(values)
    if not values:
        return None
    k = (len(values) - 1) * p / 100
    f, c = int(k), min(int(k) + 1, len(values) - 1)
    return values[f] + (values[c] - values[f]) * (k - f)


def stats(values):
    values = [v for v in values if v is not None]
    if not values:
        return None
    return {'mean': statistics.fmean(values), 'median': statistics.median(values),
            'p5': percentile(values, 5), 'p95': percentile(values, 95), 'n': len(values)}


SUMMARY_METRICS = [
    # key, label, higher is better
    ('fps', 'FPS', True),
    ('mspt_avg', 'Client MSPT (avg)', False),
    ('heap_after_gc_mb', 'Heap after GC (MB)', False),
    ('heap_used_mb', 'Heap used (MB)', False),
    ('heap_total_mb', 'Heap committed (MB)', False),
    ('rss_mb', 'Process RSS (MB)', False),
    ('nmt_total_mb', 'NMT committed total (MB)', False),
    ('nmt_java_heap_mb', 'NMT Java heap (MB)', False),
    ('nmt_class_mb', 'NMT class/metaspace (MB)', False),
    ('nmt_thread_mb', 'NMT threads (MB)', False),
    ('nmt_code_mb', 'NMT code cache (MB)', False),
    ('nmt_gc_mb', 'NMT GC (MB)', False),
    ('nmt_other_mb', 'NMT other, e.g. direct buffers (MB)', False),
    ('nmt_internal_mb', 'NMT internal (MB)', False),
    ('nmt_malloc_mb', 'NMT malloc (MB)', False),
    ('threads', 'Live threads', False),
    ('gc_per_min', 'GC pauses per minute', False),
    ('gc_pause_ms_per_min', 'GC pause time (ms per minute)', False),
]


def summarize(result_dir: Path):
    values = {}
    runs = {}
    rows_per_run = {}
    for run_dir in sorted(p for p in result_dir.iterdir() if p.is_dir()):
        info_file = run_dir / 'run.json'
        if not info_file.exists():
            continue
        info = json.loads(info_file.read_text())
        scenario = info['scenario']
        runs.setdefault(scenario, []).append(run_dir.name)
        bucket = values.setdefault(scenario, {})
        with open(run_dir / 'samples.csv') as f:
            rows = list(csv.DictReader(f))
            rows_per_run.setdefault(scenario, []).append(rows)
            for row in rows:
                for key, value in row.items():
                    if value not in ('', None) and key != 't':
                        bucket.setdefault(key, []).append(float(value))
        gc = gc_in_window(run_dir, info) if (run_dir / 'gc.log').exists() else info.get('gc', [])
        bucket.setdefault('heap_after_gc_mb', []).extend(e['after_mb'] for e in gc)
        if info.get('uptime_start') is not None and info.get('uptime_end'):
            minutes = max((info['uptime_end'] - info['uptime_start']) / 60, 1e-9)
            bucket.setdefault('gc_per_min', []).append(len(gc) / minutes)
            bucket.setdefault('gc_pause_ms_per_min', []).append(sum(e['pause_ms'] for e in gc) / minutes)

    scenarios = [s for s in SCENARIOS if s in values] + [s for s in values if s not in SCENARIOS]
    baseline = 'vanilla' if 'vanilla' in values else (scenarios[0] if scenarios else None)
    lines = [f'# Benchmark {result_dir.name}', '']
    config = result_dir / 'config.json'
    if config.exists():
        c = json.loads(config.read_text())
        lines += [f'{c.get("loader")} {c.get("mc")}, {"Xvfb" if c.get("xvfb") else "headless (-lwjgl)"}, '
                  f'{c.get("reps")} repetitions x {c.get("duration")}s after {c.get("warmup")}s warmup, '
                  f'-Xmx{c.get("xmx")}, sample every {c.get("interval")}s, dummy assets.', '']
    lines += ['Runs: ' + ', '.join(f'{s}: {len(r)}' for s, r in runs.items()), '']
    header = '| Metric | ' + ' | '.join(f'{s} mean / median / p95' for s in scenarios)
    header += ''.join(f' | {s} vs {baseline}' for s in scenarios if s != baseline) + ' |'
    lines += [header, '|' + '---|' * (1 + len(scenarios) + len([s for s in scenarios if s != baseline]))]
    table = {}
    for key, label, higher_better in SUMMARY_METRICS:
        cells, means = [], {}
        for s in scenarios:
            st = stats(values[s].get(key, []))
            means[s] = st['mean'] if st else None
            cells.append('-' if not st else f'{fmt(st["mean"])} / {fmt(st["median"])} / {fmt(st["p95"])}')
            table.setdefault(s, {})[key] = st
        if all(c == '-' for c in cells):
            continue
        deltas = []
        for s in scenarios:
            if s == baseline:
                continue
            a, b = means.get(s), means.get(baseline)
            if a is None or b is None or b == 0:
                deltas.append('-')
            else:
                pct = (a - b) / b * 100
                better = (pct > 0) == higher_better
                deltas.append(f'{pct:+.1f}%' + (' (better)' if better and abs(pct) >= 1 else ''))
        lines.append(f'| {label} | ' + ' | '.join(cells + deltas) + ' |')
    lines += ['', 'Heap after GC is taken from the GC log (heap occupancy after each pause), NMT values are committed '
                  'memory from `jcmd VM.native_memory summary`. FPS p5 per scenario: '
              + ', '.join(f'{s}: {fmt((table[s].get("fps") or {}).get("p5"))}' for s in scenarios), '']
    series = {s: {key: mean_series(rows_per_run.get(s, []), key) for key, _ in LINE_CHARTS} for s in scenarios}
    charts = write_charts(result_dir, scenarios, table, series)
    if charts:
        lines += ['## Charts', ''] + [c + '\n' for c in charts]
    text = '\n'.join(lines)
    (result_dir / 'summary.md').write_text(text)
    (result_dir / 'summary.json').write_text(json.dumps(table, indent=2))
    return text


# ----------------------------------------------------------------------------------------------------------------------
# charts (plain SVG, no dependencies)

# light colors are attributes, so every SVG renderer shows them, dark mode overrides them with CSS
LIGHT = {'bg': '#fcfcfb', 'text': '#0b0b0b', 'muted': '#52514e', 'grid': '#e4e3df',
         's1': '#2a78d6', 's2': '#eb6834', 's3': '#1baf7a', 's4': '#eda100'}
DARK = {'bg': '#1a1a19', 'text': '#f0efec', 'muted': '#c3c2b7', 'grid': '#383835',
        's1': '#3987e5', 's2': '#d95926', 's3': '#199e70', 's4': '#c98500'}
CHART_STYLE = ('<style>text { font-family: system-ui, -apple-system, "Segoe UI", sans-serif; }'
               '@media (prefers-color-scheme: dark) {'
               + ''.join(f'.{k} {{ fill: {v}; }}' for k, v in DARK.items() if k in ('bg', 'text', 'muted'))
               + f'.grid {{ stroke: {DARK["grid"]}; }}'
               + ''.join(f'.{k} {{ fill: {v}; stroke: {v}; }} .{k}.line {{ fill: none; }}'
                         for k, v in DARK.items() if k.startswith('s'))
               + '}</style>')
# metrics that get a bar chart in every compare summary, key -> title
BAR_CHARTS = [
    ('fps', 'FPS'),
    ('heap_used_mb', 'Heap used (MB)'),
    ('heap_after_gc_mb', 'Heap after GC (MB)'),
    ('rss_mb', 'Process memory, RSS (MB)'),
    ('nmt_total_mb', 'Native memory committed (MB)'),
    ('gc_per_min', 'GC pauses per minute'),
    ('gc_pause_ms_per_min', 'GC pause time (ms per minute)'),
]
# metrics that get a line chart over the measured time, key -> title
LINE_CHARTS = [
    ('heap_used_mb', 'Heap used over time (MB)'),
    ('rss_mb', 'Process memory over time (MB)'),
]


def esc(text):
    return str(text).replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')


def series_color(scenario, scenarios):
    # color follows the scenario, not its position in this chart
    known = list(SCENARIOS) if scenario in SCENARIOS else scenarios
    return f's{known.index(scenario) % 4 + 1}'


def svg_text(x, y, content, cls='muted', anchor='start', size=12, weight=400):
    return (f'<text class="{cls}" x="{x}" y="{y}" fill="{LIGHT[cls]}" text-anchor="{anchor}" '
            f'font-size="{size}" font-weight="{weight}">{esc(content)}</text>')


def nice_max(value):
    if value <= 0:
        return 1
    magnitude = 10 ** (len(str(int(value))) - 1)
    for step in (1, 2, 2.5, 5, 10):
        if step * magnitude >= value:
            return step * magnitude
    return 10 * magnitude


def svg(width, height, body):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" '
            f'viewBox="0 0 {width} {height}">{CHART_STYLE}'
            f'<rect class="bg" width="100%" height="100%" fill="{LIGHT["bg"]}"/>{body}</svg>\n')


def bar_chart(title, bars, scenarios):
    """Horizontal bars, one per scenario. bars: [(scenario, value)]."""
    width, label_w, value_w, bar_h, gap, top = 640, 110, 90, 22, 10, 44
    height = top + len(bars) * (bar_h + gap) + 8
    plot_w = width - label_w - value_w - 16
    top_value = max(v for _, v in bars) or 1
    body = [svg_text(16, 26, title, 'text', size=15, weight=600)]
    for i, (scenario, value) in enumerate(bars):
        color = series_color(scenario, scenarios)
        y = top + i * (bar_h + gap)
        w = max(plot_w * value / top_value, 2)
        body.append(svg_text(label_w - 8, y + bar_h / 2 + 4, scenario, anchor='end'))
        # square at the baseline, 4px rounded at the data end
        body.append(f'<path d="M{label_w},{y} h{w - 4:.1f} q4,0 4,4 v{bar_h - 8} q0,4 -4,4 h{-(w - 4):.1f} z" '
                    f'class="{color}" fill="{LIGHT[color]}"><title>{esc(scenario)}: {fmt(value)}</title></path>')
        body.append(svg_text(f'{label_w + w + 6:.1f}', y + bar_h / 2 + 4, fmt(value), 'text'))
    return svg(width, height, ''.join(body))


def line_chart(title, lines, scenarios):
    """lines: [(name, [(x seconds, y)])], one y axis starting at 0."""
    width, height, left, right, top, bottom = 640, 300, 56, 16, 64, 36
    plot_w, plot_h = width - left - right, height - top - bottom
    x_max = max((x for _, pts in lines for x, _ in pts), default=1) or 1
    y_max = nice_max(max((y for _, pts in lines for _, y in pts), default=1))
    body = [svg_text(16, 26, title, 'text', size=15, weight=600)]
    # legend
    lx = left
    for name, _ in lines:
        color = series_color(name, scenarios)
        body.append(f'<rect class="{color}" x="{lx}" y="38" width="12" height="12" rx="2" fill="{LIGHT[color]}"/>'
                    + svg_text(lx + 18, 48, name))
        lx += 30 + 7 * len(name)
    for i in range(5):
        value = y_max * i / 4
        y = top + plot_h - plot_h * i / 4
        body.append(f'<line class="grid" stroke="{LIGHT["grid"]}" x1="{left}" x2="{width - right}" '
                    f'y1="{y:.1f}" y2="{y:.1f}"/>' + svg_text(left - 6, f'{y + 4:.1f}', f'{value:,.0f}', anchor='end'))
    for i in range(5):
        x, seconds = left + plot_w * i / 4, x_max * i / 4
        label = f'{seconds:.0f} s' if x_max < 180 else f'{seconds / 60:.0f} min'
        anchor = 'start' if i == 0 else 'end' if i == 4 else 'middle'
        body.append(svg_text(f'{x:.1f}', height - 12, label, anchor=anchor))
    for name, pts in lines:
        coords = ' '.join(f'{left + plot_w * x / x_max:.1f},{top + plot_h - plot_h * y / y_max:.1f}' for x, y in pts)
        color = series_color(name, scenarios)
        body.append(f'<polyline class="{color} line" points="{coords}" fill="none" stroke="{LIGHT[color]}" '
                    f'stroke-width="2" stroke-linejoin="round"><title>{esc(name)}</title></polyline>')
    return svg(width, height, ''.join(body))


def write_charts(result_dir: Path, scenarios, table, series):
    """Writes the compare charts to result_dir/charts and returns markdown that embeds them."""
    chart_dir = result_dir / 'charts'
    chart_dir.mkdir(exist_ok=True)
    markdown = []
    for key, title in BAR_CHARTS:
        bars = [(s, table[s][key]['mean']) for s in scenarios if table[s].get(key)]
        if len(bars) < 1:
            continue
        (chart_dir / f'{key}.svg').write_text(bar_chart(title, bars, scenarios))
        markdown.append(f'![{title}](charts/{key}.svg)')
    for key, title in LINE_CHARTS:
        lines = [(s, series[s][key]) for s in scenarios if series.get(s, {}).get(key)]
        if not lines:
            continue
        (chart_dir / f'{key}-over-time.svg').write_text(line_chart(title, lines, scenarios))
        markdown.append(f'![{title}](charts/{key}-over-time.svg)')
    return markdown


def mean_series(runs, key):
    """Averages a metric of several runs per sample index, x is seconds since the start of each run."""
    columns = []
    for rows in runs:
        pts = [(float(r['t']), float(r[key])) for r in rows if r.get(key) not in ('', None)]
        if pts:
            columns.append([(t - pts[0][0], v) for t, v in pts])
    if not columns:
        return []
    length = min(len(c) for c in columns)
    return [(statistics.fmean(c[i][0] for c in columns), statistics.fmean(c[i][1] for c in columns))
            for i in range(length)]


def fmt(value):
    if value is None:
        return '-'
    if abs(value) >= 1000:
        return f'{value:,.0f}'
    return f'{value:.1f}'


def cmd_summarize(args):
    print(summarize(Path(args.result_dir)))


# ----------------------------------------------------------------------------------------------------------------------
# leak

HISTO_LINE = re.compile(r'^\s*\d+:\s+(\d+)\s+(\d+)\s+(\S+)')


def parse_histogram(text):
    result = {}
    for line in text.splitlines():
        m = HISTO_LINE.match(line)
        if m:
            name = m.group(3)
            count, size = result.get(name, (0, 0))
            result[name] = (count + int(m.group(1)), size + int(m.group(2)))
    return result


def growth_report(snapshots, min_growth_bytes, top, remap):
    """Classes whose retained bytes grew in (almost) every histogram, histograms are taken after a full GC."""
    if len(snapshots) < 3:
        return 'Not enough histograms for a trend, run longer or with a shorter --histogram-interval.\n'
    names = set()
    for _, histogram in snapshots:
        names.update(histogram)
    rows = []
    n = len(snapshots)
    for name in names:
        if 'FillerElement' in name or 'FillerArray' in name:
            continue  # dead space the GC fills heap regions with, not real objects
        sizes = [h.get(name, (0, 0))[1] for _, h in snapshots]
        counts = [h.get(name, (0, 0))[0] for _, h in snapshots]
        growth = sizes[-1] - sizes[0]
        if growth < min_growth_bytes:
            continue
        increases = sum(1 for a, b in zip(sizes, sizes[1:]) if b > a)
        decreases = sum(1 for a, b in zip(sizes, sizes[1:]) if b < a)
        # least squares slope in bytes per minute
        ts = [t for t, _ in snapshots]
        mt, ms = statistics.fmean(ts), statistics.fmean(sizes)
        var = sum((t - mt) ** 2 for t in ts)
        slope = sum((t - mt) * (s - ms) for t, s in zip(ts, sizes)) / var * 60 if var else 0
        monotonic = decreases == 0
        rows.append((monotonic, increases / (n - 1), slope, name, sizes[0], sizes[-1], counts[0], counts[-1]))
    rows.sort(key=lambda r: (not r[0], -r[1], -r[2]))
    out = [f'{n} histograms over {(snapshots[-1][0] - snapshots[0][0]) / 60:.1f} minutes, classes that grew by at least '
           f'{min_growth_bytes // 1024} KB, monotonic growth first:', '',
           '| Class | Monotonic | Growing steps | Bytes/min | Bytes first -> last | Instances first -> last |',
           '|---|---|---|---|---|---|']
    for monotonic, ratio, slope, name, s0, s1, c0, c1 in rows[:top]:
        out.append(f'| `{remap.cls(name)}` | {"yes" if monotonic else "no"} | {ratio:.0%} | {slope:,.0f} | {s0:,} -> {s1:,} | {c0:,} -> {c1:,} |')
    if not rows:
        out.append('| (none) | | | | | |')
    return '\n'.join(out) + '\n'


def summarize_old_objects(jfr_tool, recording: Path, remap, top=25):
    """Groups the JFR OldObjectSample events (objects that survived for long) by type and the root they are reachable from."""
    result = subprocess.run([jfr_tool, 'print', '--json', '--events', 'jdk.OldObjectSample', str(recording)],
                            capture_output=True, text=True)
    if result.returncode != 0:
        return f'jfr print failed: {result.stderr}\n'
    try:
        events = json.loads(result.stdout)['recording']['events']
    except (ValueError, KeyError):
        return 'Could not parse the jfr output\n'
    by_type = {}
    for event in events:
        values = event.get('values', {})
        obj = values.get('object') or {}
        type_name = remap.cls((((obj.get('type') or {}).get('name')) or '?').replace('/', '.'))
        root = values.get('root') or {}
        root_desc = root.get('description') or root.get('type') or root.get('system') or '?'
        frames = ((values.get('stackTrace') or {}).get('frames') or [])
        site = '?'
        for frame in frames:
            method = frame.get('method') or {}
            owner = (((method.get('type') or {}).get('name')) or '?').replace('/', '.')
            if not owner.startswith(('java.', 'jdk.', 'sun.')):
                site = f'{remap.cls(owner)}.{remap.method(method.get("name"))}:{frame.get("lineNumber")}'
                break
        key = (type_name, site, str(root_desc))
        by_type[key] = by_type.get(key, 0) + 1
    out = [f'{len(events)} old object samples (allocations that were still alive at the end), grouped:', '',
           '| Samples | Type | First non-JDK allocation site | GC root |', '|---|---|---|---|']
    for (type_name, site, root), count in sorted(by_type.items(), key=lambda kv: -kv[1])[:top]:
        out.append(f'| {count} | `{type_name}` | `{site}` | {root} |')
    return '\n'.join(out) + '\n'


def cmd_leak(args):
    env = Env(args)
    stamp = time.strftime('%Y%m%d-%H%M%S')
    scenario = args.scenario
    run_dir = new_run_dir(env.dir / 'results', f'leak-{scenario}-{stamp}')
    (run_dir / 'config.json').write_text(json.dumps(vars(args), indent=2, default=str))
    install, optimize_off = SCENARIOS[scenario]
    mods = [env.specifics_jar] + ([env.optimizations_jar] if install else [])
    jfr = run_dir / 'leak.jfr'
    jvm = jvm_memory_args(args, run_dir) + [
        f'-XX:StartFlightRecording=filename={jfr},settings=profile,path-to-gc-roots=true,dumponexit=true']
    game = Game(env, run_dir, mods, jvm, xvfb=args.xvfb)
    copy_template(env, game.game_dir)
    remap = Remapper(env)
    snapshots, samples = [], []
    histo_dir = run_dir / 'histograms'
    histo_dir.mkdir()
    try:
        game.wait_for('HMC-Specifics initialized!', 900)
        game.find_pid()
        game.wait_in_world(600)
        prepare_world(game)
        if optimize_off:
            game.send('optimize off')
        log(f'In world, warming up for {args.warmup}s')
        time.sleep(args.warmup)
        if args.heap_dumps:
            log('Heap dump (start)')
            game.jcmd('GC.heap_dump', str(run_dir / 'start.hprof'))
        start = time.time()
        next_histo = start
        next_move = start + args.move_every if args.move_every else None
        while time.time() - start < args.duration:
            tick = time.time()
            if tick >= next_histo:
                # GC.class_histogram runs a full GC first, so only live objects are counted
                text = game.jcmd('GC.class_histogram')
                (histo_dir / f'{int(tick - start):06d}.txt').write_text(text)
                snapshots.append((tick - start, parse_histogram(text)))
                log(f'Histogram {len(snapshots)}, heap {sum(s for _, s in snapshots[-1][1].values()) // 1048576} MB live')
                next_histo = tick + args.histogram_interval
            if next_move and tick >= next_move:
                game.send(f'/tp @s ~{args.move_distance} ~ ~')
                next_move = tick + args.move_every
            samples.append(sample(game, with_nmt=True))
            time.sleep(max(0.0, args.interval - (time.time() - tick)))
        text = game.jcmd('GC.class_histogram')
        (histo_dir / f'{int(time.time() - start):06d}.txt').write_text(text)
        snapshots.append((time.time() - start, parse_histogram(text)))
        if args.heap_dumps:
            log('Heap dump (end)')
            game.jcmd('GC.heap_dump', str(run_dir / 'end.hprof'))
        game.jcmd('JFR.dump', f'filename={jfr}')
    finally:
        code = game.quit()
        log('Game exited with', code)
        if not args.keep_game:
            shutil.rmtree(run_dir / 'game', ignore_errors=True)

    with open(run_dir / 'samples.csv', 'w', newline='') as f:
        writer = csv.DictWriter(f, fieldnames=SAMPLE_FIELDS)
        writer.writeheader()
        writer.writerows(samples)
    memory = [(name, [(float(r['t']) - float(samples[0]['t']), float(r[key])) for r in samples if r.get(key) is not None])
              for name, key in (('Heap used', 'heap_used_mb'), ('RSS', 'rss_mb'))] if samples else []
    if any(pts for _, pts in memory):
        (run_dir / 'charts').mkdir(exist_ok=True)
        (run_dir / 'charts' / 'memory.svg').write_text(line_chart('Memory over time (MB)', memory, ['Heap used', 'RSS']))

    movement = (f'teleporting {args.move_distance} blocks every {args.move_every}s' if args.move_every
                else 'standing still')
    report = [f'# Leak hunt {run_dir.name}', '', f'{env.loader} {env.mc}, scenario {scenario}, {args.duration}s, {movement}.', '']
    first, last = (samples[0], samples[-1]) if samples else ({}, {})
    report += ['## Memory over time', '', '| | start | end |', '|---|---|---|']
    for key in ('heap_used_mb', 'rss_mb', 'nmt_total_mb', 'nmt_java_heap_mb', 'nmt_class_mb', 'nmt_other_mb',
                'nmt_malloc_mb', 'threads', 'chunks', 'entities'):
        report.append(f'| {key} | {first.get(key)} | {last.get(key)} |')
    if (run_dir / 'charts' / 'memory.svg').exists():
        report += ['', '![Memory over time (MB)](charts/memory.svg)']
    live = [(t, sum(s for _, s in h.values())) for t, h in snapshots]
    report += ['', 'Live heap after full GC (from the class histograms): '
               + ', '.join(f'{t / 60:.0f}min {b / 1048576:.0f}MB' for t, b in live), '']
    report += ['## Growing classes', '', growth_report(snapshots, args.min_growth_kb * 1024, args.top, remap)]
    report += ['## Long lived allocations (JFR OldObjectSample)', '']
    if jfr.exists():
        report.append(summarize_old_objects(java_bin(env.java_home, 'jfr'), jfr, remap))
    else:
        report.append('No JFR recording.\n')
    mat = TOOLS_DIR / 'mat' / 'ParseHeapDump.sh'
    end_dump = run_dir / 'end.hprof'
    report += ['## Eclipse MAT', '']
    if args.heap_dumps and mat.exists() and end_dump.exists():
        log('Running Eclipse MAT leak suspects, this takes a while')
        mat_env = dict(os.environ, JAVA_HOME=env.java_home, PATH=f'{env.java_home}/bin:{os.environ.get("PATH", "")}')
        result = subprocess.run([str(mat), '-vm', java_bin(env.java_home), str(end_dump), 'org.eclipse.mat.api:suspects'],
                                capture_output=True, text=True, env=mat_env, cwd=run_dir)  # MAT puts its workspace in cwd
        (run_dir / 'mat.log').write_text(result.stdout + result.stderr)
        reports = sorted(run_dir.glob('end_Leak_Suspects*.zip'))
        report.append(f'Leak suspects report: `{reports[0].name}` (unzip and open index.html)\n' if reports
                      else f'MAT did not produce a report, see mat.log (exit code {result.returncode})\n')
    else:
        report.append('Skipped (needs `setup --mat` and heap dumps).\n')
    text = '\n'.join(report)
    (run_dir / 'leak-report.md').write_text(text)
    print(text)


# ----------------------------------------------------------------------------------------------------------------------
# render hotspots

# (category, class name prefixes). The first match wins, so more specific prefixes come first. Only code that renders,
# no game logic, networking or integrated server.
RENDER_CATEGORIES = [
    ('hmc lwjgl redirection', ('me.earth.headlessmc.lwjgl', 'io.github.headlesshq.headlessmc.lwjgl', 'me.earth.headlessmc.runtime')),
    ('lwjgl / GL', ('org.lwjgl.opengl', 'org.lwjgl.glfw', 'org.lwjgl.system', 'org.lwjgl.stb', 'org.lwjgl')),
    ('textures', ('net.minecraft.client.renderer.texture', 'net.minecraft.client.resources.metadata.animation',
                  'com.mojang.blaze3d.platform.NativeImage', 'com.mojang.blaze3d.platform.TextureUtil')),
    ('blaze3d', ('com.mojang.blaze3d.systems', 'com.mojang.blaze3d.vertex', 'com.mojang.blaze3d.shaders',
                 'com.mojang.blaze3d.pipeline', 'com.mojang.blaze3d.platform', 'com.mojang.blaze3d.font',
                 'com.mojang.blaze3d.framegraph', 'com.mojang.blaze3d.resource', 'com.mojang.blaze3d.buffers',
                 'com.mojang.blaze3d.opengl', 'com.mojang.blaze3d.textures', 'com.mojang.blaze3d')),
    ('gui', ('net.minecraft.client.gui',)),
    ('particles', ('net.minecraft.client.particle',)),
    ('models', ('net.minecraft.client.model', 'net.minecraft.client.resources.model')),
    ('renderer', ('net.minecraft.client.renderer',)),
    ('other client rendering', ('net.minecraft.client.Camera', 'net.minecraft.client.Options', 'net.minecraft.client.OptionInstance',
                                'net.minecraft.client.FogRenderer', 'net.minecraft.client.ClientBrandRetriever')),
]
# parts of the renderer packages that are not rendering
NOT_RENDER = ('com.mojang.blaze3d.audio', 'net.minecraft.client.gui.components.toasts.SystemToast')
SKIP_THREADS = ('Server thread', 'Netty', 'IO-Worker', 'Download-', 'Thread-', 'DestroyJavaVM', 'Sound ', 'main')


def render_category(class_name):
    if class_name.startswith(NOT_RENDER):
        return None
    for category, prefixes in RENDER_CATEGORIES:
        if class_name.startswith(prefixes):
            return category
    return None


FRAME = re.compile(r'^\s+([^\s(]+)\.([^.\s(]+)\(')
THREAD = re.compile(r'(?:sampledThread|eventThread) = "([^"]*)"')
OBJECT_CLASS = re.compile(r'objectClass = (\S+)')
WEIGHT = re.compile(r'weight = ([\d.,]+) (bytes|kB|MB|GB|TB)')
UNITS = {'bytes': 1, 'kB': 1000, 'MB': 1000 ** 2, 'GB': 1000 ** 3, 'TB': 1000 ** 4}


def read_jfr_events(jfr_tool, recording: Path, event, depth):
    """Streams `jfr print` of one event type: yields (thread, [(class, method), ...] leaf first, object class, weight)."""
    proc = subprocess.Popen([jfr_tool, 'print', '--events', event, '--stack-depth', str(depth), str(recording)],
                            stdout=subprocess.PIPE, text=True, errors='replace')
    thread, frames, obj, weight, in_stack = None, [], None, 0, False
    for line in proc.stdout:
        if line.startswith('jdk.'):
            thread, frames, obj, weight, in_stack = None, [], None, 0, False
        elif line.startswith('}'):
            yield thread, frames, obj, weight
        elif 'stackTrace = [' in line:
            in_stack = True
        elif in_stack:
            if line.strip() == ']':
                in_stack = False
                continue
            m = FRAME.match(line)
            if m:
                frames.append((m.group(1), m.group(2)))
        else:
            m = THREAD.search(line)
            if m:
                thread = m.group(1)
                continue
            m = OBJECT_CLASS.search(line)
            if m:
                obj = m.group(1)
                continue
            m = WEIGHT.search(line)
            if m:
                weight = float(m.group(1).replace(',', '')) * UNITS[m.group(2)]
    proc.wait()


class Profile:
    """Aggregates stack samples (CPU samples count 1 each, allocation samples their weight in bytes)."""

    def __init__(self, remap: Remapper):
        self.remap = remap
        self.total = 0.0
        self.render_total = 0.0
        self.by_thread = {}
        self.render_by_thread = {}
        self.leaf = {}        # leaf-most render frame -> weight: where the render path itself spends its time
        self.inclusive = {}   # any render frame on the stack (once per sample) -> weight
        self.category = {}    # category of the leaf-most render frame -> weight
        self.hot_line_leaf = {}  # the real top frame (JDK or not) under the leaf-most render frame -> weight
        self.objects = {}     # allocation only: allocated class of render path allocations -> weight
        self.entries = {}     # outermost render frame -> tree
        self.names = {}

    def name(self, cls, method):
        key = (cls, method)
        n = self.names.get(key)
        if n is None:
            c = self.remap.cls(cls)
            n = self.names[key] = (c, self.remap.method(method), render_category(c))
        return n

    def add(self, thread, frames, obj, weight, only_render=False):
        named = [self.name(c, m) for c, m in frames]
        leaf = next((i for i, f in enumerate(named) if f[2]), None)
        if only_render and leaf is None:
            return  # a thread in a native method is usually just blocked (Netty epoll, stdin), not working
        self.total += weight
        self.by_thread[thread] = self.by_thread.get(thread, 0) + weight
        if leaf is None:
            return
        if thread and thread.startswith(SKIP_THREADS):
            return  # e.g. the integrated server or startup code, not part of the render path
        self.render_total += weight
        self.render_by_thread[thread] = self.render_by_thread.get(thread, 0) + weight
        key = f'{named[leaf][0]}.{named[leaf][1]}'
        self.leaf[key] = self.leaf.get(key, 0) + weight
        self.category[named[leaf][2]] = self.category.get(named[leaf][2], 0) + weight
        top = f'{named[0][0]}.{named[0][1]}'
        self.hot_line_leaf[(key, top)] = self.hot_line_leaf.get((key, top), 0) + weight
        for k in {f'{f[0]}.{f[1]}' for f in named if f[2]}:
            self.inclusive[k] = self.inclusive.get(k, 0) + weight
        if obj:
            o = self.remap.cls(obj)
            self.objects[(key, o)] = self.objects.get((key, o), 0) + weight
        # the call tree starts at the outermost render frame and ends at the leaf-most render frame
        outer = max(i for i, f in enumerate(named) if f[2])
        path = [f'{f[0].rsplit(".", 1)[-1]}.{f[1]}' for f in reversed(named[leaf:outer + 1])]
        node = self.entries.setdefault(path[0], {'w': 0, 'children': {}})
        node['w'] += weight
        for step in path[1:]:
            node = node['children'].setdefault(step, {'w': 0, 'children': {}})
            node['w'] += weight


def tree_lines(root_name, node, total, render_total, min_share, max_depth, depth=0, prefix=''):
    out = [f'{prefix}{node["w"] / total * 100:5.2f}% of all, {node["w"] / render_total * 100:5.1f}% of render  {root_name}']
    if depth >= max_depth:
        return out
    children = sorted(node['children'].items(), key=lambda kv: -kv[1]['w'])
    children = [c for c in children if c[1]['w'] / render_total >= min_share]
    for name, child in children:
        out += tree_lines(name, child, total, render_total, min_share, max_depth, depth + 1, prefix + '    ')
    return out


def fmt_bytes(value):
    for unit in ('B', 'KB', 'MB', 'GB', 'TB'):
        if abs(value) < 1024 or unit == 'TB':
            return f'{value:.1f} {unit}'
        value /= 1024


def hotspot_tables(profile: Profile, unit_name, top, value_fmt, render_thread_total, header):
    """Markdown tables: by category, leaf-most render frame, inclusive."""
    t, r = profile.total or 1, profile.render_total
    rt = profile.by_thread.get('Render thread') or 1
    out = [f'{header}: {value_fmt(profile.total)} in total, {value_fmt(r)} ({r / t * 100:.1f}%) with a render frame on the stack '
           f'(outside the server, networking and startup threads).', '']
    out += ['By thread (all samples, and the part that is render path):', '', '| Thread | Total | Render path | Share of thread |', '|---|---|---|---|']
    for thread, w in sorted(profile.by_thread.items(), key=lambda kv: -kv[1])[:8]:
        rw = profile.render_by_thread.get(thread, 0)
        out.append(f'| {thread} | {value_fmt(w)} ({w / t * 100:.1f}%) | {value_fmt(rw)} ({rw / t * 100:.1f}%) | {rw / w * 100:.1f}% |')
    out += ['', 'By category (the render frame closest to the top of the stack gets the sample):', '',
            f'| Category | {unit_name} | % of all | % of render path |', '|---|---|---|---|']
    for cat, w in sorted(profile.category.items(), key=lambda kv: -kv[1]):
        out.append(f'| {cat} | {value_fmt(w)} | {w / t * 100:.2f}% | {w / (r or 1) * 100:.1f}% |')
    out += ['', 'Render path on the "Render thread" only: '
            f'{value_fmt(profile.render_by_thread.get("Render thread", 0))} of {value_fmt(profile.by_thread.get("Render thread", 0))} '
            f'({profile.render_by_thread.get("Render thread", 0) / rt * 100:.1f}%).']
    out += ['', f'Top {top} render methods by own cost (samples whose closest-to-top render frame is this method, '
                f'including what it calls outside of rendering code, e.g. JDK collections):', '',
            f'| # | Method | Category | {unit_name} | % of all | % of render path | Typical callee at the top of the stack |',
            '|---|---|---|---|---|---|---|']
    tops = {}
    for (key, topframe), w in profile.hot_line_leaf.items():
        if key not in tops or w > tops[key][1]:
            tops[key] = (topframe, w)
    for i, (key, w) in enumerate(sorted(profile.leaf.items(), key=lambda kv: -kv[1])[:top], 1):
        cls, _, meth = key.rpartition('.')
        out.append(f'| {i} | `{key}` | {render_category(cls)} | {value_fmt(w)} | {w / t * 100:.2f}% | {w / (r or 1) * 100:.1f}% | '
                   f'`{tops[key][0] if tops[key][0] != key else "(itself)"}` |')
    out += ['', f'Top {top} render methods by inclusive cost (the method or something it calls, anywhere on the stack):', '',
            f'| # | Method | {unit_name} | % of all | % of render path |', '|---|---|---|---|---|']
    for i, (key, w) in enumerate(sorted(profile.inclusive.items(), key=lambda kv: -kv[1])[:top], 1):
        out.append(f'| {i} | `{key}` | {value_fmt(w)} | {w / t * 100:.2f}% | {w / (r or 1) * 100:.1f}% |')
    return out


def cmd_render(args):
    env = Env(args)
    if args.scenario == 'vanilla':
        log('Warning: profiling vanilla shows the whole render pipeline, not what is left with the optimizations on')
    stamp = time.strftime('%Y%m%d-%H%M%S')
    run_dir = new_run_dir(env.dir / 'results', f'render-{args.scenario}-{stamp}')
    (run_dir / 'config.json').write_text(json.dumps(vars(args), indent=2, default=str))
    install, optimize_off = SCENARIOS[args.scenario]
    mods = [env.specifics_jar] + ([env.optimizations_jar] if install else [])
    jfr = run_dir / 'render.jfr'
    # DebugNonSafepoints makes the samples point at the real line instead of the next safepoint
    jvm = jvm_memory_args(args, run_dir) + ['-XX:+UnlockDiagnosticVMOptions', '-XX:+DebugNonSafepoints',
                                            f'-XX:FlightRecorderOptions:stackdepth={args.stack_depth}']
    game = Game(env, run_dir, mods, jvm, xvfb=args.xvfb)
    copy_template(env, game.game_dir)
    try:
        game.wait_for('HMC-Specifics initialized!', 900)
        game.find_pid()
        game.wait_in_world(600)
        prepare_world(game)
        if optimize_off:
            game.send('optimize off')
        log(f'In world, warming up for {args.warmup}s')
        time.sleep(args.warmup)
        # started after the warmup, so that loading screens, atlas stitching and JIT warmup are not in the recording
        out = game.jcmd('JFR.start', 'name=render', 'settings=profile', f'filename={jfr}',
                        f'jdk.ExecutionSample#period={args.cpu_period}ms', f'jdk.NativeMethodSample#period={args.cpu_period}ms',
                        'jdk.ObjectAllocationSample#enabled=true', f'jdk.ObjectAllocationSample#throttle={args.alloc_rate}/s')
        log(out.strip().splitlines()[0] if out.strip() else 'JFR.start gave no output')
        start = time.time()
        next_move = start + args.move_every if args.move_every else None
        fps = []
        while time.time() - start < args.duration:
            tick = time.time()
            if next_move and tick >= next_move:
                game.send(f'/tp @s ~{args.move_distance} ~ ~')
                next_move = tick + args.move_every
            data = game.metrics('client.fps') or {}
            fps.append(data.get('client.fps'))
            time.sleep(max(0.0, 5 - (time.time() - tick)))
        game.jcmd('JFR.dump', 'name=render', f'filename={jfr}')
        game.jcmd('JFR.stop', 'name=render')
    finally:
        code = game.quit()
        log('Game exited with', code)
        if not args.keep_game:
            shutil.rmtree(run_dir / 'game', ignore_errors=True)
    if not jfr.exists():
        raise SystemExit('No JFR recording was written')
    write_render_report(env, args, run_dir, jfr)


def cmd_analyze(args):
    jfr = Path(args.jfr).resolve()
    write_render_report(Env(args), args, jfr.parent, jfr)


def write_render_report(env, args, run_dir: Path, jfr: Path):
    remap = Remapper(env)
    jfr_tool = java_bin(env.java_home, 'jfr')
    log('Analyzing', jfr)
    cpu = Profile(remap)
    for event in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
        for thread, frames, _, _ in read_jfr_events(jfr_tool, jfr, event, args.stack_depth):
            cpu.add(thread, frames, None, 1, only_render=event == 'jdk.NativeMethodSample')
    alloc = Profile(remap)
    for thread, frames, obj, weight in read_jfr_events(jfr_tool, jfr, 'jdk.ObjectAllocationSample', args.stack_depth):
        alloc.add(thread, frames, obj, weight)

    count = lambda v: f'{v:,.0f}'
    report = [f'# Render hotspots {run_dir.name}', '',
              f'{env.loader} {env.mc}, scenario {args.scenario}, {args.duration}s recorded after {args.warmup}s warmup, '
              f'CPU sample every {args.cpu_period} ms, allocation sample throttle {args.alloc_rate}/s. '
              f'Raw recording for JDK Mission Control: `{jfr.name}`.', '',
              'Names are Mojang names' + (' (translated from Fabric intermediary).' if remap.classes else '.'),
              'CPU samples are JFR ExecutionSamples (threads running Java code). Threads inside native methods '
              '(NativeMethodSample, e.g. real GL calls) are only counted when a render method is on their stack, '
              'because blocked threads (Netty, stdin) show up there too. Idle and parked threads are not sampled, so '
              '"% of all" is a share of the Java code that actually ran, and "% of render thread" a share of the '
              'samples of the thread named "Render thread".', '']
    report += ['## CPU', ''] + hotspot_tables(cpu, 'Samples', args.top, count, 0, 'CPU samples')
    report += ['', '### Call trees of the render path', '',
               f'Each tree starts at the outermost render method of a stack and ends at the render method closest to the '
               f'top. Branches below {args.tree_min}% of the render path are pruned.', '', '```']
    total, render_total = cpu.total or 1, cpu.render_total or 1
    for name, node in sorted(cpu.entries.items(), key=lambda kv: -kv[1]['w'])[:args.trees]:
        report += tree_lines(name, node, total, render_total, args.tree_min / 100, 14)
        report.append('')
    report += ['```', '']
    report += ['## Allocations', ''] + hotspot_tables(alloc, 'Allocated', args.top, fmt_bytes, 0, 'Allocation weight')
    objects = {}
    for (key, obj), w in alloc.objects.items():
        objects[obj] = objects.get(obj, 0) + w
    report += ['', 'Classes allocated on the render path:', '', '| Class | Allocated | % of all | Main allocating render method |', '|---|---|---|---|']
    main_site = {}
    for (key, obj), w in alloc.objects.items():
        if obj not in main_site or w > main_site[obj][1]:
            main_site[obj] = (key, w)
    for obj, w in sorted(objects.items(), key=lambda kv: -kv[1])[:args.top]:
        report.append(f'| `{obj}` | {fmt_bytes(w)} | {w / (alloc.total or 1) * 100:.2f}% | `{main_site[obj][0]}` |')
    text = '\n'.join(report) + '\n'
    (run_dir / 'render-hotspots.md').write_text(text)
    print(text)


# ----------------------------------------------------------------------------------------------------------------------

def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest='command', required=True)

    def common(p):
        p.add_argument('--mc', required=True, help='Minecraft version, e.g. 1.21.4')
        p.add_argument('--loader', required=True, choices=sorted(LOADER_TYPES), help='Modloader')
        p.add_argument('--java-home', required=True, help='Java to run Minecraft with (also jcmd/jfr)')
        p.add_argument('--hmc-java-home', help=f'Java {HMC_JAVA}+ to run HeadlessMc with, defaults to --java-home')
        p.add_argument('--xmx', default='2G', help='Max heap of Minecraft (default 2G)')
        p.add_argument('--xvfb', action='store_true', help='Render with Xvfb instead of HeadlessMc headless mode')

    def running(p):
        p.add_argument('--warmup', type=int, default=60, help='Seconds in the world before measuring (default 60)')
        p.add_argument('--interval', type=float, default=5, help='Seconds between samples (default 5)')
        p.add_argument('--move-every', type=int, default=0, help='Teleport the player every n seconds (0 = stand still)')
        p.add_argument('--move-distance', type=int, default=256, help='Blocks to teleport along x (default 256)')
        p.add_argument('--keep-game', action='store_true', help='Keep the game directories of the runs')

    p = sub.add_parser('setup', help='Download tools and install Minecraft')
    common(p)
    p.add_argument('--jar', help='hmc-optimizations release jar, defaults to the one built in this repository')
    p.add_argument('--build', action='store_true', help='Build the hmc-optimizations jar first')
    p.add_argument('--mat', action='store_true', help='Download Eclipse MAT for leak suspect reports')
    p.set_defaults(func=cmd_setup)

    p = sub.add_parser('world', help='Create the template world (vanilla client, HMC-Specifics clicks through the menus)')
    common(p)
    p.add_argument('--generate', type=int, default=30, help='Seconds to let the world generate (default 30)')
    p.set_defaults(func=cmd_world)

    p = sub.add_parser('compare', help='Compare the scenarios')
    common(p)
    running(p)
    p.add_argument('--scenarios', default='vanilla,optimized', help=f'Comma separated, of {", ".join(SCENARIOS)}')
    p.add_argument('--reps', type=int, default=3, help='Repetitions per scenario (default 3)')
    p.add_argument('--duration', type=int, default=300, help='Measured seconds per run (default 300)')
    p.add_argument('--nmt-interval', type=float, default=15, help='Seconds between NMT samples (default 15)')
    p.add_argument('--attempts', type=int, default=5, help='Tries per run before it is skipped (default 5)')
    p.set_defaults(func=cmd_compare)

    p = sub.add_parser('leak', help='Long run that looks for memory leaks')
    common(p)
    running(p)
    p.set_defaults(move_every=30, interval=15)
    p.add_argument('--scenario', default='optimized', choices=list(SCENARIOS))
    p.add_argument('--duration', type=int, default=3600, help='Measured seconds (default 3600)')
    p.add_argument('--histogram-interval', type=int, default=300, help='Seconds between class histograms (default 300)')
    p.add_argument('--heap-dumps', action='store_true', help='Take heap dumps at start and end (large files)')
    p.add_argument('--min-growth-kb', type=int, default=256, help='Ignore classes that grew less (default 256 KB)')
    p.add_argument('--top', type=int, default=30, help='Rows in the reports (default 30)')
    p.set_defaults(func=cmd_leak)

    p = sub.add_parser('render', help='Profile the client with JFR and rank the render work that is left')
    common(p)
    running(p)
    p.set_defaults(move_every=30)
    p.add_argument('--scenario', default='optimized', choices=list(SCENARIOS))
    p.add_argument('--duration', type=int, default=180, help='Recorded seconds (default 180)')
    p.add_argument('--cpu-period', type=int, default=10, help='Milliseconds between CPU samples (default 10)')
    p.add_argument('--alloc-rate', type=int, default=2000, help='Allocation samples per second, JFR throttle (default 2000)')
    p.add_argument('--stack-depth', type=int, default=96, help='Frames per sample (default 96)')
    p.add_argument('--top', type=int, default=30, help='Rows in the tables (default 30)')
    p.add_argument('--trees', type=int, default=6, help='Call trees to print (default 6)')
    p.add_argument('--tree-min', type=float, default=2.0, help='Prune call tree branches below this %% of the render path (default 2)')
    p.set_defaults(func=cmd_render)

    p = sub.add_parser('analyze', help='Analyze the render.jfr of an earlier render run again')
    common(p)
    p.add_argument('jfr')
    p.add_argument('--scenario', default='optimized')
    p.add_argument('--duration', type=int, default=0)
    p.add_argument('--warmup', type=int, default=0)
    p.add_argument('--cpu-period', type=int, default=10)
    p.add_argument('--alloc-rate', type=int, default=2000)
    p.add_argument('--stack-depth', type=int, default=96)
    p.add_argument('--top', type=int, default=30)
    p.add_argument('--trees', type=int, default=6)
    p.add_argument('--tree-min', type=float, default=2.0)
    p.set_defaults(func=cmd_analyze)

    p = sub.add_parser('summarize', help='Summarize a compare result directory again')
    p.add_argument('result_dir')
    p.set_defaults(func=cmd_summarize)

    args = parser.parse_args(argv)
    args.func(args)


if __name__ == '__main__':
    main()
