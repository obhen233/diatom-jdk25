<template>
  <div class="eclipse-ide" :class="themeClass">
    <!-- Login Overlay -->
    <div v-if="showLogin" class="login-overlay">
      <div class="login-box">
        <div class="login-lang">
          <select class="lang-select" :value="getLang()" @change="setLang($event.target.value)" :title="t('languageLabel')">
            <option value="en">EN</option>
            <option value="zh">中文</option>
          </select>
        </div>
        <div class="login-title">{{ t('loginTitle') }}</div>
        <div class="login-hint">{{ t('loginHint') }}</div>
        <input class="login-input" v-model="loginForm.username" :placeholder="t('username')" @keyup.enter="doLogin" />
        <input class="login-input" type="password" v-model="loginForm.password" :placeholder="t('password')" @keyup.enter="doLogin" />
        <div class="login-error" v-if="loginForm.error">{{ loginForm.error }}</div>
        <button class="login-btn" @click="doLogin" :disabled="loginForm.loading">
          {{ loginForm.loading ? t('loggingIn') : t('login') }}
        </button>
      </div>
    </div>

    <!-- Kick Alert -->
    <div v-if="kickAlert.visible" class="dialog-overlay" @click.stop>
      <div class="dialog-box" @click.stop>
        <div class="dialog-title">{{ t('kickTitle') }}</div>
        <div style="margin:12px 0;font-size:13px;color:var(--text-primary)">
          {{ t('kickMessage', { user: kickAlert.newUser }) }}
          <br><span style="color:var(--text-secondary);font-size:12px">{{ t('kickTimeout') }}</span>
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="respondKick(false)">{{ t('kickReject') }}</button>
          <button class="dialog-btn primary" @click="respondKick(true)">{{ t('kickAllow') }}</button>
        </div>
      </div>
    </div>

    <!-- Header Bar (merged menu + toolbar) -->
    <div class="header-bar" :class="themeClass">
      <span class="header-brand">☕ {{ t('javaIde') }}</span>
      <div class="tool-sep"></div>
      <button class="tool-btn" :title="t('saveTitle')" @click="handleSave">💾</button>
      <div class="tool-sep"></div>
      <!-- Run button group -->
      <div class="run-group">
        <button class="tool-btn run-btn" :title="t('run') + ' (Ctrl+F11)'" @click="doSubmit()" :disabled="loading">
          <span v-if="!loading">▶</span>
          <span v-else class="spinner"></span>
        </button>
        <button class="tool-btn run-config-btn" :title="t('runConfig')" @click="showRunConfigPanel = !showRunConfigPanel">
          ▾
        </button>
      </div>
      <button class="tool-btn stop-btn" :title="t('stop')" :disabled="!loading" @click="doStop">⬛</button>
      <button class="tool-btn rebuild-btn" :title="t('rebuild')" @click="doRebuild" :disabled="loading">🔄</button>
      <button class="tool-btn deploy-btn" :title="t('deploy')" @click="startDeploy" :disabled="deployRunning" v-if="deployHasYaml && !deployReconnecting">
        <span v-if="deployRunning" class="deploy-spinner">⏳</span>
        <span v-else>🚀</span>
      </button>
      <button class="tool-btn reconnect-btn" :title="t('deployReconnect')" @click="doDeployReconnect" v-if="deployReconnecting">🔌</button>
      <div class="tool-sep"></div>
      <!-- Debug buttons -->
      <button class="tool-btn" :title="t('debugStart')" @click="doDebugStart" :disabled="debugState !== 'disconnected' || debugReconnectAvailable" v-show="!debugReconnectAvailable">🐛</button>
      <button class="tool-btn reconnect-btn" :title="t('debugReconnect')" @click="doDebugReconnect" :disabled="debugState !== 'disconnected'" v-show="debugReconnectAvailable">🔌</button>
      <button class="tool-btn" :title="t('debugStop')" @click="doDebugStop" :disabled="debugState === 'disconnected'">⬛</button>
      <button class="tool-btn" :title="t('debugContinue')" @click="doDebugContinue" :disabled="debugState !== 'suspended'">▶</button>
      <button class="tool-btn" :title="t('debugStepOver')" @click="doDebugStepOver" :disabled="debugState !== 'suspended'">⬇</button>
      <button class="tool-btn" :title="t('debugStepInto')" @click="doDebugStepInto" :disabled="debugState !== 'suspended'">↘</button>
      <button class="tool-btn" :title="t('debugStepOut')" @click="doDebugStepOut" :disabled="debugState !== 'suspended'">⬆</button>
      <div class="tool-sep"></div>
      <button class="tool-btn" :title="t('search') + ' (Ctrl+T)'" @click="openSearch('all')">🔍</button>
      <div class="tool-sep"></div>
      <button class="tool-btn" :title="t('settings')" @click="showSettingsPanel = true">⚙</button>
      <div class="header-spacer"></div>
      <!-- Run config summary -->
      <span class="run-config-label" v-if="runConfig.mainClass" @click="showRunConfigPanel = !showRunConfigPanel" :title="t('clickEditRunConfig')">
        {{ runConfig.mainClass.split('.').pop() }}
      </span>
      <span class="menu-item theme-toggle" @click="toggleTheme" :title="isDark ? t('switchToLight') : t('switchToDark')">
        {{ isDark ? '☀️' : '🌙' }}
      </span>
      <select class="lang-select" :value="getLang()" @change="setLang($event.target.value)" :title="t('languageLabel')">
        <option value="en">EN</option>
        <option value="zh">中文</option>
      </select>
      <span class="menu-item user-info" v-if="authUser" :title="t('currentUser') + ': ' + authUser">
        👤 {{ authUser }}
      </span>
      <span class="menu-item logout-btn" v-if="authUser" @click="doLogout" :title="t('logout')">
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
          <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/>
          <polyline points="16 17 21 12 16 7"/>
          <line x1="21" y1="12" x2="9" y2="12"/>
        </svg>
      </span>
    </div>

    <!-- Run Configuration Panel -->
    <div v-if="showRunConfigPanel" class="run-config-panel" :class="themeClass" @click.stop>
      <div class="run-config-header">
        <span>▶ {{ t('runConfiguration') }}</span>
        <span class="run-config-close" @click="showRunConfigPanel = false">✕</span>
      </div>
      <div class="run-config-body">
        <div class="run-config-row">
          <label>{{ t('mainClass') }}</label>
          <div class="run-config-main-class">
            <input v-model="runConfig.mainClass" :placeholder="t('autoDetect')" />
            <button @click="detectMainClasses" :title="t('autoDetectBtn')">🔍</button>
          </div>
          <div v-if="detectedMainClasses.length > 1" class="detected-classes">
            <div v-for="cls in detectedMainClasses" :key="cls" class="detected-class-item"
                 @click="runConfig.mainClass = cls; detectedMainClasses = []">
              {{ cls }}
            </div>
          </div>
        </div>
        <div class="run-config-row">
          <label>{{ t('programArgs') }}</label>
          <input v-model="runConfig.programArgs" :placeholder="t('programArgsHint')" />
        </div>
        <div class="run-config-row">
          <label>{{ t('jvmArgs') }}</label>
          <input v-model="runConfig.jvmArgs" :placeholder="t('jvmArgsHint')" />
        </div>
        <div class="run-config-row">
          <label>{{ t('timeout') }}</label>
          <input v-model="runConfig.timeLimit" :placeholder="t('timeoutHintFull')" type="number" />
        </div>
      </div>
    </div>

    <!-- Workbench -->
    <div class="workbench">
      <!-- Sidebar -->
      <div class="sidebar" :style="{ width: sidebarWidth + 'px' }">
        <div class="view-header">
          <span class="view-title">{{ t('packageExplorer') }}</span>
          <div class="view-actions">
            <span class="view-action" :title="t('newProject')" @click="showNewProjectDialog">＋</span>
            <span class="view-action" :title="t('addToWorkspace')" @click="showRestoreProjectDialog">📥</span>
            <span class="view-action" :title="t('refresh')" @click="loadProjects">🔄</span>
          </div>
        </div>
        <div class="package-tree">
          <div v-if="projects.length === 0" class="tree-empty">{{ t('workspaceEmpty') }}</div>
          <template v-for="project in projects" :key="project.name">
            <div class="tree-node root"
                 :class="{ active: activeProject === project.name }"
                 @click="selectProject(project.name)"
                 @dblclick="toggleProjectExpand(project.name)"
                 @contextmenu.prevent="showContextMenu($event, project.name, '', 'project', '')">
              <span class="icon">{{ expandedProjects[project.name] ? '📂' : '📦' }}</span>
              <span class="label">{{ project.name }}</span>
              <span class="tag" v-if="project.type !== 'plain'">{{ project.type }}</span>
              <span class="project-refresh-btn" :title="t('refreshProject')" @click.stop="doRefreshProject(project.name)">🔄</span>
            </div>
            <template v-if="expandedProjects[project.name] && projectTrees[project.name]">
              <tree-node-item
                v-for="child in projectTrees[project.name]"
                :key="project.name + '/' + child.name + '/' + treeVersion"
                :node="child" :depth="1" :project="project.name"
                :active-file="activeFile"
                :reveal-path="revealPath"
                @select-file="onSelectFile"
                @ctx-menu="onTreeContextMenu"
              />
            </template>
            <!-- Libraries node -->
            <div v-if="expandedProjects[project.name]" class="tree-node"
                 :style="{ paddingLeft: '24px' }"
                 @click="toggleLibExpand(project.name)"
                 @contextmenu.prevent="showContextMenu($event, project.name, 'lib', 'libs', '')">
              <span class="icon">{{ expandedLibs[project.name] ? '📂' : '📚' }}</span>
              <span class="label">{{ t('libraries') }}</span>
            </div>
            <template v-if="expandedLibs[project.name] && projectLibs[project.name]">
              <!-- Jars from lib/ -->
              <div v-for="jar in projectLibs[project.name].jars" :key="project.name+'/lib/'+jar.name"
                   class="tree-node" :style="{ paddingLeft: '40px' }"
                   @contextmenu.prevent="showContextMenu($event, project.name, 'lib/'+jar.name, 'jar', jar.name)">
                <span class="icon">🫙</span>
                <span class="label">{{ jar.name }}</span>
                <span class="tag size">{{ jar.size }}</span>
              </div>
              <!-- Maven/Gradle dependencies -->
              <template v-if="projectLibs[project.name].dependencies && projectLibs[project.name].dependencies.length > 0">
                <div class="tree-node" :style="{ paddingLeft: '40px' }" style="color:#999;font-style:italic;cursor:default;">
                  <span class="icon">📋</span>
                  <span class="label">{{ projectLibs[project.name].projectType === 'maven' ? t('mavenDependencies') : t('gradleDependencies') }}</span>
                </div>
                <div v-for="dep in projectLibs[project.name].dependencies"
                     :key="project.name+'/dep/'+dep.groupId+':'+dep.artifactId"
                     class="tree-node" :style="{ paddingLeft: '56px' }" style="cursor:default;">
                  <span class="icon">📦</span>
                  <span class="label dep-label">{{ dep.groupId }}:{{ dep.artifactId }}{{ dep.version ? ':'+dep.version : '' }}</span>
                  <span class="tag scope">{{ dep.scope }}</span>
                </div>
              </template>
              <div v-if="(!projectLibs[project.name].jars || projectLibs[project.name].jars.length === 0) && (!projectLibs[project.name].dependencies || projectLibs[project.name].dependencies.length === 0)"
                   class="tree-node" :style="{ paddingLeft: '40px' }" style="color:#666;font-style:italic;cursor:default;">
                <span class="label">{{ t('noLibraries') }}</span>
              </div>
            </template>
          </template>
        </div>
      </div>

      <div class="resize-handle-v" @mousedown="startResizeSidebar"></div>

      <!-- Center -->
      <div class="center-area">
        <div class="editor-area">
          <div class="editor-tabs-bar">
            <div class="editor-tab" v-for="tab in openTabs" :key="tab.key"
                 :class="{ active: currentTab === tab.key }"
                 @click="switchTab(tab.key)"
                 @contextmenu.prevent="showTabContextMenu($event, tab.key)">
              <span class="tab-icon">☕</span>
              <span class="tab-label">{{ tab.label }}</span>
              <span class="tab-dirty" v-if="dirtyTabs[tab.key]">●</span>
              <span class="tab-close" @click.stop="closeTab(tab.key)">×</span>
            </div>
          </div>
          <div ref="editorContainer" class="editor-container"></div>
        </div>

    <!-- Tab Context Menu -->
    <div v-if="tabCtxMenu.visible" class="context-menu" :style="{ left: tabCtxMenu.x + 'px', top: tabCtxMenu.y + 'px' }" @click.stop>
      <div class="context-menu-item" @click="tabCtxClose">✕ {{ t('closeTab') }}</div>
      <div class="context-menu-item" @click="tabCtxCloseOthers">✕ {{ t('closeOthers') }}</div>
      <div class="context-menu-item" @click="tabCtxCloseLeft">⬅ {{ t('closeLeft') }}</div>
      <div class="context-menu-item" @click="tabCtxCloseRight">➡ {{ t('closeRight') }}</div>
      <div class="context-menu-sep"></div>
      <div class="context-menu-item" @click="tabCtxCloseAll">✕ {{ t('closeAll') }}</div>
    </div>

        <div class="resize-handle-h" @mousedown="startResizeConsole"></div>

        <!-- Console -->
        <div class="console-area" :style="{ height: consoleHeight + 'px' }">
          <div class="console-tabs-bar">
            <div class="console-tab" :class="{ active: consoleTab === 'console' }" @click="consoleTab = 'console'">📋 {{ t('console') }}</div>
            <div class="console-tab" :class="{ active: consoleTab === 'problems' }" @click="consoleTab = 'problems'">
              ⚠ {{ t('problems') }} <span class="badge" v-if="problems.length > 0">{{ problems.length }}</span>
            </div>
            <div class="console-tab" :class="{ active: consoleTab === 'git' }" @click="onGitTabClick">
              🔀 {{ t('git') }}
            </div>
            <div class="console-tab" :class="{ active: consoleTab === 'terminal' }" @click="consoleTab = 'terminal'">
              >_ {{ t('terminal') }}
            </div>
            <div class="console-tab" :class="{ active: consoleTab === 'debug' }" @click="consoleTab = 'debug'" v-show="debugShowTab">
              🐛 {{ t('debugPanel') }}
            </div>
            <div class="console-spacer"></div>
            <!-- Git 操作按钮（仅在 Git tab 激活时显示） -->
            <template v-if="consoleTab === 'git' && gitInfo.initialized">
              <span class="console-btn vcs-branch" :title="t('switchBranch')" @click="showGitBranchDialog" style="cursor:pointer">🌿 {{ gitInfo.branch }}</span>
              <span class="console-btn" :title="t('commit')" @click="showGitCommitDialog">📝</span>
              <span class="console-btn" :title="t('push')" @click="doGitPush">⬆</span>
              <span class="console-btn" :title="t('pull')" @click="doGitPull">⬇</span>
              <span class="console-btn" :title="t('switchBranch')" @click="showGitBranchDialog">🔀</span>
              <span class="console-btn" :title="t('merge')" @click="showGitMergeDialog">🔗</span>
              <span class="console-btn" :title="t('refresh')" @click="loadGitInfo">🔄</span>
            </template>
            <template v-if="consoleTab === 'git' && !gitInfo.initialized">
              <span class="console-btn" :title="t('initGit')" @click="doGitInit">📦 {{ t('initGit') }}</span>
              <span class="console-btn" :title="t('cloneRepo')" @click="showGitCloneDialog">📥 {{ t('cloneRepo') }}</span>
            </template>
            <span class="console-btn" :title="t('clear')" @click="consoleTab === 'terminal' ? clearTerminal() : clearConsole()" v-if="consoleTab !== 'git'">🗑</span>
          </div>
          <div class="console-body" ref="consoleBody">
            <div v-show="consoleTab === 'console'" class="console-content">
              <div v-for="(line, i) in consoleLines" :key="i" :class="['console-line', line.type]">{{ line.text }}</div>
              <div v-if="consoleLines.length === 0" class="console-empty">{{ t('runCodeHint') }}</div>
            </div>
            <div v-show="consoleTab === 'problems'" class="problems-content">
              <table class="problems-table" v-if="problems.length > 0">
                <thead><tr><th style="width:30px"></th><th>{{ t('description') }}</th><th style="width:120px">{{ t('resource') }}</th><th style="width:60px">{{ t('line') }}</th></tr></thead>
                <tbody>
                  <tr v-for="(p, i) in problems" :key="i" class="problem-row" @click="goToProblem(p)">
                    <td>{{ p.severity === 'error' ? '❌' : '⚠️' }}</td><td>{{ p.message }}</td><td>{{ p.resource }}</td><td>{{ p.line }}</td>
                  </tr>
                </tbody>
              </table>
              <div v-else class="console-empty">{{ t('noProblems') }}</div>
            </div>
            <!-- Git History Tab -->
            <div v-show="consoleTab === 'git'" class="git-content">
              <VcsCommitHistory
                :project="activeProject"
                :initialized="gitInfo.initialized"
                :commits="gitCommits"
                :loading="gitLogLoading"
                :has-more="gitCommits.length >= 50"
                @refresh="loadVcsInfo()"
                @cherry-pick="doGitCherryPick"
                @view-diff="showCommitDiff"
                @load-more="loadGitLog(gitCommits.length)"
              />
            </div>
            <!-- Terminal Tab -->
            <div v-show="consoleTab === 'terminal'" class="terminal-content">
              <XtermTerminal ref="xtermTerminalRef" :projectName="activeProject || ''"
                :activeFile="activeFile || ''"
                :isDark="isDark"
                :auth-token="authToken"
                @apply-code="onTerminalApplyCode"
                @insert-code="onTerminalInsertCode"
                @refresh-project="onTerminalRefreshProject"
                @refresh-editor="onTerminalRefreshEditor"
                @active-ai-task="onTerminalActiveAiTask"
                @open-file="onTerminalOpenFile" />
            </div>
            <!-- Debug Panel -->
            <div v-show="consoleTab === 'debug'" class="debug-panel">
              <div class="debug-section">
                <div class="debug-section-title">🔴 {{ t('debugBreakpoint') }}s ({{ debugBreakpointList.length }})</div>
                <div v-if="debugBreakpointList.length === 0" class="debug-empty">{{ t('debugNoBreakpoints') }}</div>
                <div v-for="bp in debugBreakpointList" :key="bp.key"
                     class="debug-breakpoint-item"
                     @click="navigateToBreakpoint(bp)">
                  <input type="checkbox" :checked="bp.enabled" class="debug-bp-checkbox"
                         @click.stop @change="toggleBreakpointEnabled(bp)" />
                  <span class="debug-bp-location" :class="{ 'debug-bp-disabled': !bp.enabled }">
                    {{ (bp.filePath || bp.fileName) }}<span class="debug-bp-line">:{{ bp.lineNumber }}</span>
                  </span>
                  <button class="debug-bp-remove" @click.stop="removeBreakpointById(bp)" :title="t('removeBreakpoint')">✕</button>
                </div>
              </div>
              <div class="debug-section">
                <div class="debug-section-title">{{ t('debugStackFrames') }}</div>
                <div v-if="debugStackFrames.length === 0" class="debug-empty">{{ t('debugNoFrames') }}</div>
                <div v-for="(frame, fi) in debugStackFrames" :key="fi"
                     class="debug-frame-item" :class="{ active: fi === 0 }"
                     @click="selectDebugFrame(frame.threadId, frame.frameId)">
                  <span class="debug-frame-name">{{ frame.className }}.{{ frame.methodName }}</span>
                  <span class="debug-frame-location">{{ frame.fileName }}:{{ frame.lineNumber }}</span>
                </div>
              </div>
              <div class="debug-section">
                <div class="debug-section-title">{{ t('debugVariables') }}</div>
                <div v-if="debugVariables.length === 0" class="debug-empty">{{ t('debugNoVariables') }}</div>
                <div v-for="(v, vi) in debugVariables" :key="vi" class="debug-variable-item">
                  <span class="debug-var-name">{{ v.name }}</span>
                  <span class="debug-var-sep">=</span>
                  <span class="debug-var-value" :class="{ 'debug-var-null': v.nul }">{{ v.nul ? 'null' : v.value }}</span>
                  <span class="debug-var-type" v-if="v.type">: {{ v.type }}</span>
                  <div v-if="v.children && v.children.length > 0" class="debug-var-children">
                    <div v-for="(child, ci) in v.children" :key="ci" class="debug-variable-item debug-child-item">
                      <span class="debug-var-name">{{ child.name }}</span>
                      <span class="debug-var-sep">=</span>
                      <span class="debug-var-value" :class="{ 'debug-var-null': child.nul }">{{ child.nul ? 'null' : child.value }}</span>
                      <span class="debug-var-type" v-if="child.type">: {{ child.type }}</span>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="right-tool-bar">
        <div class="right-tool-tab" :class="{ active: rightPanel === 'maven' }"
             v-if="activeProjectType === 'maven'"
             @click="rightPanel = rightPanel === 'maven' ? '' : 'maven'; if(rightPanel === 'maven') loadMavenPanel()">
          <span class="right-tool-tab-text">{{ t('maven') }}</span>
        </div>
        <div class="right-tool-tab" :class="{ active: rightPanel === 'gradle' }"
             v-if="activeProjectType === 'gradle'"
             @click="rightPanel = rightPanel === 'gradle' ? '' : 'gradle'; if(rightPanel === 'gradle') loadGradlePanel()">
          <span class="right-tool-tab-text">{{ t('gradle') }}</span>
        </div>
        <div class="right-tool-tab" :class="{ active: rightPanel === 'vcs' }"
             @click="rightPanel = rightPanel === 'vcs' ? '' : 'vcs'; if(rightPanel === 'vcs') loadVcsInfo()">
          <span class="right-tool-tab-text">{{ activeProjectVcsType === 'svn' ? 'SVN' : activeProjectVcsType === 'git' ? t('git') : 'VCS' }}</span>
        </div>
      </div>

      <!-- Maven Tool Window -->
      <div v-if="rightPanel === 'maven'" class="right-panel" :style="{ width: rightPanelWidth + 'px' }">
        <div class="resize-handle-v right-resize" @mousedown="startResizeRightPanel"></div>
        <div class="right-panel-inner">
          <div class="right-panel-header">
            <span class="right-panel-title">{{ t('maven') }}</span>
            <div class="right-panel-actions">
              <span class="view-action" :title="t('refresh')" @click="loadMavenPanel">🔄</span>
              <span class="view-action" :title="t('close')" @click="rightPanel = ''">✕</span>
            </div>
          </div>
          <div class="right-panel-toolbar">
            <span class="rp-tool-btn" :title="t('mavenReimport')" @click="doMavenBuild('dependency:resolve')">🔄</span>
            <span class="rp-tool-btn" :title="t('mavenGenerateSources')" @click="doMavenBuild('generate-sources')">📄</span>
            <span class="rp-tool-btn" :title="t('mavenDownloadSources')" @click="doMavenBuild('dependency:sources')">⬇</span>
            <span class="rp-tool-btn" :title="t('mavenGoal')" @click="showMavenGoalDialog">▶</span>
          </div>
          <div class="right-panel-body">
            <!-- Project node -->
            <div class="rp-tree-node root" @click="mvnExpanded.root = !mvnExpanded.root">
              <span class="rp-icon">{{ mvnExpanded.root ? '▼' : '▶' }}</span>
              <span class="rp-icon-img">Ⓜ</span>
              <span class="rp-label">{{ activeProject }}</span>
            </div>
            <template v-if="mvnExpanded.root">
              <!-- Lifecycle -->
              <div class="rp-tree-node" style="padding-left:20px" @click="mvnExpanded.lifecycle = !mvnExpanded.lifecycle">
                <span class="rp-icon">{{ mvnExpanded.lifecycle ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">📂</span>
                <span class="rp-label">{{ t('lifecycle') }}</span>
              </div>
              <template v-if="mvnExpanded.lifecycle">
                <div v-for="phase in mavenLifecycle" :key="phase" class="rp-tree-node rp-leaf"
                     style="padding-left:40px" @dblclick="doMavenBuild(phase)">
                  <span class="rp-icon-img">⚙</span>
                  <span class="rp-label">{{ phase }}</span>
                </div>
              </template>
              <!-- Plugins -->
              <div class="rp-tree-node" style="padding-left:20px" @click="mvnExpanded.plugins = !mvnExpanded.plugins">
                <span class="rp-icon">{{ mvnExpanded.plugins ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">📂</span>
                <span class="rp-label">{{ t('plugins') }}</span>
              </div>
              <template v-if="mvnExpanded.plugins">
                <template v-for="plugin in mavenPlugins" :key="plugin.artifactId">
                  <div class="rp-tree-node" style="padding-left:40px"
                       @click="plugin._expanded = !plugin._expanded; mavenPlugins = [...mavenPlugins]">
                    <span class="rp-icon">{{ plugin._expanded ? '▼' : '▶' }}</span>
                    <span class="rp-icon-img">🔌</span>
                    <span class="rp-label rp-plugin-label">{{ plugin.artifactId }}</span>
                  </div>
                  <template v-if="plugin._expanded && plugin.goals">
                    <div v-for="goal in plugin.goals" :key="plugin.artifactId+':'+goal" class="rp-tree-node rp-leaf"
                         style="padding-left:60px" @dblclick="doMavenBuild(plugin.artifactId + ':' + goal)">
                      <span class="rp-icon-img">⚙</span>
                      <span class="rp-label">{{ plugin.artifactId }}:{{ goal }}</span>
                    </div>
                  </template>
                </template>
                <div v-if="mavenPlugins.length === 0" class="rp-tree-empty" style="padding-left:40px">{{ t('noPlugins') }}</div>
              </template>
              <!-- Run Configurations -->
              <div class="rp-tree-node" style="padding-left:20px" @click="mvnExpanded.runConfigs = !mvnExpanded.runConfigs">
                <span class="rp-icon">{{ mvnExpanded.runConfigs ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">⚙</span>
                <span class="rp-label">{{ t('runConfigurations') }}</span>
              </div>
              <template v-if="mvnExpanded.runConfigs">
                <div v-for="cls in mavenMainClasses" :key="cls" class="rp-tree-node rp-leaf"
                     style="padding-left:40px" @dblclick="runConfig.mainClass = cls; doSubmit(cls)">
                  <span class="rp-icon-img">▶</span>
                  <span class="rp-label">{{ cls }}</span>
                </div>
                <div v-if="mavenMainClasses.length === 0" class="rp-tree-empty" style="padding-left:40px">
                  {{ t('noMainClassDetected') }}
                </div>
              </template>
              <!-- Dependencies -->
              <div class="rp-tree-node" style="padding-left:20px" @click="mvnExpanded.deps = !mvnExpanded.deps">
                <span class="rp-icon">{{ mvnExpanded.deps ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">📊</span>
                <span class="rp-label">{{ t('dependencies') }}</span>
              </div>
              <template v-if="mvnExpanded.deps">
                <div v-for="dep in mavenDeps" :key="dep.groupId+':'+dep.artifactId" class="rp-tree-node rp-leaf"
                     style="padding-left:40px">
                  <span class="rp-icon-img">📦</span>
                  <span class="rp-label rp-dep-label">{{ dep.groupId }}:{{ dep.artifactId }}:{{ dep.version }}</span>
                  <span class="rp-tag">{{ dep.scope }}</span>
                </div>
                <div v-if="mavenDeps.length === 0" class="rp-tree-empty" style="padding-left:40px">{{ t('noDeps') }}</div>
              </template>
            </template>
          </div>
        </div>
      </div>

      <!-- Gradle Tool Window -->
      <div v-if="rightPanel === 'gradle'" class="right-panel" :style="{ width: rightPanelWidth + 'px' }">
        <div class="resize-handle-v right-resize" @mousedown="startResizeRightPanel"></div>
        <div class="right-panel-inner">
          <div class="right-panel-header">
            <span class="right-panel-title">{{ t('gradle') }}</span>
            <div class="right-panel-actions">
              <span class="view-action" :title="t('refresh')" @click="loadGradlePanel">🔄</span>
              <span class="view-action" :title="t('close')" @click="rightPanel = ''">✕</span>
            </div>
          </div>
          <div class="right-panel-toolbar">
            <span class="rp-tool-btn" :title="t('gradleRefresh')" @click="loadGradlePanel">🔄</span>
            <span class="rp-tool-btn" :title="t('runGradleTask')" @click="showGradleTaskDialog">▶</span>
          </div>
          <div class="right-panel-body">
            <div class="rp-tree-node root" @click="gradleExpanded.root = !gradleExpanded.root">
              <span class="rp-icon">{{ gradleExpanded.root ? '▼' : '▶' }}</span>
              <span class="rp-icon-img">🐘</span>
              <span class="rp-label">{{ activeProject }}</span>
            </div>
            <template v-if="gradleExpanded.root">
              <!-- Tasks -->
              <div class="rp-tree-node" style="padding-left:20px" @click="gradleExpanded.tasks = !gradleExpanded.tasks">
                <span class="rp-icon">{{ gradleExpanded.tasks ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">📂</span>
                <span class="rp-label">{{ t('tasks') }}</span>
              </div>
              <template v-if="gradleExpanded.tasks">
                <div v-for="task in gradleTasks" :key="task" class="rp-tree-node rp-leaf"
                     style="padding-left:40px" @dblclick="doGradleBuild(task)">
                  <span class="rp-icon-img">⚙</span>
                  <span class="rp-label">{{ task }}</span>
                </div>
              </template>
              <!-- Run Configurations -->
              <div class="rp-tree-node" style="padding-left:20px" @click="gradleExpanded.runConfigs = !gradleExpanded.runConfigs">
                <span class="rp-icon">{{ gradleExpanded.runConfigs ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">⚙</span>
                <span class="rp-label">{{ t('runConfigurations') }}</span>
              </div>
              <template v-if="gradleExpanded.runConfigs">
                <div v-for="cls in gradleMainClasses" :key="cls" class="rp-tree-node rp-leaf"
                     style="padding-left:40px" @dblclick="runConfig.mainClass = cls; doSubmit(cls)">
                  <span class="rp-icon-img">▶</span>
                  <span class="rp-label">{{ cls }}</span>
                </div>
                <div v-if="gradleMainClasses.length === 0" class="rp-tree-empty" style="padding-left:40px">
                  {{ t('noMainClassDetected') }}
                </div>
              </template>
              <!-- Dependencies -->
              <div class="rp-tree-node" style="padding-left:20px" @click="gradleExpanded.deps = !gradleExpanded.deps">
                <span class="rp-icon">{{ gradleExpanded.deps ? '▼' : '▶' }}</span>
                <span class="rp-icon-img">📊</span>
                <span class="rp-label">{{ t('dependencies') }}</span>
              </div>
              <template v-if="gradleExpanded.deps">
                <div v-for="dep in gradleDeps" :key="dep.groupId+':'+dep.artifactId" class="rp-tree-node rp-leaf"
                     style="padding-left:40px">
                  <span class="rp-icon-img">📦</span>
                  <span class="rp-label rp-dep-label">{{ dep.groupId }}:{{ dep.artifactId }}:{{ dep.version }}</span>
                  <span class="rp-tag">{{ dep.scope }}</span>
                </div>
                <div v-if="gradleDeps.length === 0" class="rp-tree-empty" style="padding-left:40px">{{ t('noDeps') }}</div>
              </template>
            </template>
          </div>
        </div>
      </div>

      <!-- VCS Tool Window -->
      <VcsSidebar
        v-if="rightPanel === 'vcs'"
        :visible="rightPanel === 'vcs'"
        :git-info="gitInfo"
        :svn-info="svnInfo"
        :vcs-type="activeProjectVcsType"
        :project="activeProject"
        @refresh="loadVcsInfo"
        @stage="handleVcsStage"
        @unstage="handleVcsUnstage"
        @discard="handleVcsDiscard"
        @commit="showVcsCommitDialog"
        @push="doGitPush"
        @pull="doVcsPull"
        @show-branch-dialog="showGitBranchDialog"
        @close="rightPanel = ''"
      />
    </div>

    <!-- Context Menu -->
    <div v-if="ctxMenu.visible" class="context-menu" :style="{ left: ctxMenu.x + 'px', top: ctxMenu.y + 'px' }" @click.stop>
      <!-- Project context -->
      <template v-if="ctxMenu.kind === 'project'">
        <div class="context-menu-item" @click="ctxNewFile">📄 {{ t('newFile') }}</div>
        <div class="context-menu-item" @click="ctxNewPackage">📦 {{ t('newPackage') }}</div>
        <div class="context-menu-item" @click="ctxNewDir">📁 {{ t('newDirectory') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" @click="ctxUploadJar">📥 {{ t('importJar') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" @click="ctxGitOpen">🔀 {{ t('gitMenu') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" @click="ctxRenameProject">📝 {{ t('rename') }}</div>
        <div class="context-menu-item" @click="ctxRemoveProject">📤 {{ t('removeFromWorkspace') }}</div>
        <div class="context-menu-item danger" @click="ctxDeleteProject">🗑 {{ t('physicalDelete') }}</div>
      </template>
      <!-- Folder/Package context -->
      <template v-if="ctxMenu.kind === 'folder' || ctxMenu.kind === 'package'">
        <div class="context-menu-item" @click="ctxNewFile">📄 {{ t('newFile') }}</div>
        <div class="context-menu-item" v-if="ctxMenu.kind === 'package'" @click="ctxNewPackage">📦 {{ t('newSubPackage') }}</div>
        <div class="context-menu-item" @click="ctxNewDir">📁 {{ t('newSubDir') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" @click="ctxRename">📝 {{ t('rename') }}</div>
        <div class="context-menu-item danger" @click="ctxDelete">🗑 {{ t('delete') }}</div>
      </template>
      <!-- File context -->
      <template v-if="ctxMenu.kind === 'file'">
        <div class="context-menu-item" @click="ctxRunMain">▶ {{ t('runMain') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item" @click="ctxRename">📝 {{ t('rename') }}</div>
        <div class="context-menu-item danger" @click="ctxDelete">🗑 {{ t('delete') }}</div>
      </template>
      <!-- Libs node context -->
      <template v-if="ctxMenu.kind === 'libs'">
        <div class="context-menu-item" @click="ctxUploadJar">📥 {{ t('importJar') }}</div>
      </template>
      <!-- Jar context -->
      <template v-if="ctxMenu.kind === 'jar'">
        <div class="context-menu-item" @click="ctxBrowseJarClasses">📂 {{ t('decompileBrowseClasses') }}</div>
        <div class="context-menu-sep"></div>
        <div class="context-menu-item danger" @click="ctxDeleteJar">🗑 {{ t('deleteJar') }}</div>
      </template>
    </div>

    <!-- Dialog Overlay -->
    <div v-if="dialog.visible" class="dialog-overlay" @click="dialog.visible = false">
      <div class="dialog-box" @click.stop>
        <div class="dialog-title">{{ dialog.title }}</div>
        <input class="dialog-input" v-model="dialog.value" @keyup.enter="dialog.onConfirm"
               ref="dialogInput" :placeholder="dialog.placeholder" />
        <div class="dialog-actions">
          <button class="dialog-btn" @click="dialog.visible = false">{{ t('cancel') }}</button>
          <button class="dialog-btn primary" @click="dialog.onConfirm">{{ t('confirm') }}</button>
        </div>
      </div>
    </div>

    <!-- New Project Dialog -->
    <div v-if="newProjectDialog.visible" class="dialog-overlay" @click="newProjectDialog.visible = false">
      <div class="dialog-box" @click.stop>
        <div class="dialog-title">{{ t('newProject') }}</div>
        <div class="dialog-field">
          <label class="dialog-label">{{ t('newProjectName') }}</label>
          <input class="dialog-input" v-model="newProjectDialog.name" placeholder="my-project" @keyup.enter="doCreateProject" />
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="newProjectDialog.visible = false">{{ t('cancel') }}</button>
          <button class="dialog-btn primary" @click="doCreateProject">{{ t('confirm') }}</button>
        </div>
      </div>
    </div>

    <!-- Hidden file input for jar upload -->
    <input type="file" ref="jarFileInput" accept=".jar" style="display:none" @change="onJarFileSelected" />

    <!-- Branch Switch Dialog -->
    <div v-if="branchDialog.visible" class="dialog-overlay" @click="branchDialog.visible = false">
      <div class="dialog-box branch-dialog" @click.stop>
        <div class="dialog-title">{{ t('switchBranchTitle') }}</div>
        <div class="branch-current" v-if="branchDialog.currentBranch">
          {{ t('current') }}: <b>{{ branchDialog.currentBranch }}</b>
        </div>
        <input class="dialog-input" v-model="branchDialog.filter" :placeholder="t('searchOrNewBranch')" />
        <div class="branch-list" v-if="filteredBranches.length > 0">
          <div v-for="b in filteredBranches" :key="b"
               class="branch-item" :class="{ active: b === branchDialog.currentBranch }"
               @click="doSwitchBranch(b, false)">
            <span class="branch-icon">{{ b.startsWith('origin/') ? '☁' : '🌿' }}</span>
            <span class="branch-name">{{ b }}</span>
            <span class="branch-current-tag" v-if="b === branchDialog.currentBranch">{{ t('current') }}</span>
          </div>
        </div>
        <div v-else class="branch-empty">{{ t('noBranchMatch') }}</div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="branchDialog.visible = false">{{ t('cancel') }}</button>
          <button class="dialog-btn primary"
                  :disabled="!branchDialog.filter.trim() || branchDialog.loading"
                  @click="doSwitchBranch(branchDialog.filter.trim(), true)"
                  :title="t('branchIfNotExist')">
            {{ branchDialog.loading ? '...' : t('createAndSwitch') }}
          </button>
        </div>
      </div>
    </div>

    <!-- Restore Project Dialog -->
    <div v-if="restoreDialog.visible" class="dialog-overlay" @click="restoreDialog.visible = false">
      <div class="dialog-box" @click.stop style="width: 400px;">
        <div class="dialog-title">{{ t('restoreProject') }}</div>
        <div v-if="restoreDialog.loading" style="padding: 16px; text-align: center; color: #999;">{{ t('loadingText') }}</div>
        <div v-else-if="restoreDialog.items.length === 0" style="padding: 16px; text-align: center; color: #999;">{{ t('noRemovedProjects') }}</div>
        <div v-else style="max-height: 300px; overflow-y: auto; padding: 4px 0;">
          <div v-for="item in restoreDialog.items" :key="item.dirName"
               style="display: flex; align-items: center; justify-content: space-between; padding: 6px 12px; cursor: pointer; border-radius: 4px;"
               :style="{ background: 'transparent' }"
               @mouseenter="$event.currentTarget.style.background='var(--list-hover, rgba(128,128,128,0.15))'"
               @mouseleave="$event.currentTarget.style.background='transparent'"
               @click="doRestoreProject(item)">
            <span>📦 {{ item.originalName }}</span>
            <span style="font-size: 12px; color: #999;">{{ t('restore') }}</span>
          </div>
        </div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="restoreDialog.visible = false">{{ t('close') }}</button>
        </div>
      </div>
    </div>

    <!-- Settings Panel -->
    <div v-if="showSettingsPanel" class="dialog-overlay" @click="showSettingsPanel = false">
      <div class="settings-panel" @click.stop>
        <div class="settings-header">
          <span class="settings-title">⚙ {{ t('settingsTitle') }}</span>
          <span class="settings-close" @click="showSettingsPanel = false">×</span>
        </div>
        <div class="settings-body">
          <!-- 主题 -->
          <div class="settings-group">
            <div class="settings-group-title">{{ t('appearance') }}</div>
            <div class="settings-row">
              <label class="settings-label">{{ t('theme') }}</label>
              <select v-model="ideSettings.theme" @change="onThemeChange" class="settings-select">
                <option value="dark">{{ t('themeDark') }}</option>
                <option value="light">{{ t('themeLight') }}</option>
              </select>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('languageLabel') }}</label>
              <select v-model="ideSettings.language" @change="onLanguageChange" class="settings-select">
                <option value="en">English</option>
                <option value="zh">中文</option>
              </select>
            </div>
          </div>
          <!-- 编译器 -->
          <div class="settings-group">
            <div class="settings-group-title">{{ t('compiler') }}</div>
            <div class="settings-row">
              <label class="settings-label">{{ t('outputJdkVersion') }}</label>
              <select v-model="ideSettings.jdkVersion" class="settings-select">
                <option v-for="v in jdkVersions" :key="v" :value="v">JDK {{ v }}</option>
              </select>
            </div>
          </div>
          <!-- 环境路径 -->
          <div class="settings-group">
            <div class="settings-group-title">{{ t('envConfig') }}</div>
            <div class="settings-row">
              <label class="settings-label">{{ t('javaHome') }}</label>
              <input v-model="ideSettings.javaHome" class="settings-input" :placeholder="t('javaHomeHint')" />
              <span class="settings-hint" v-if="!ideSettings.javaHome">{{ t('notConfiguredDefault') }}</span>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('mavenHome') }}</label>
              <input v-model="ideSettings.mavenHome" class="settings-input" :placeholder="t('mavenHomeHint')" />
              <span class="settings-hint" v-if="!ideSettings.mavenHome">{{ t('notConfigured') }}</span>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('mavenUserSettings') }}</label>
              <input v-model="ideSettings.mavenUserSettings" class="settings-input" :placeholder="t('mavenSettingsHint')" />
              <span class="settings-hint" v-if="!ideSettings.mavenUserSettings">{{ t('notConfigured') }}</span>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('localRepository') }}</label>
              <input v-model="ideSettings.mavenLocalRepository" class="settings-input" :placeholder="t('mavenRepoHint')" />
              <span class="settings-hint" v-if="!ideSettings.mavenLocalRepository">{{ t('notConfigured') }}</span>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('gradleUserHome') }}</label>
              <input v-model="ideSettings.gradleUserHome" class="settings-input" :placeholder="t('gradleHomeHint')" />
              <span class="settings-hint" v-if="!ideSettings.gradleUserHome">{{ t('notConfigured') }}</span>
            </div>
          </div>
          <!-- 版本控制 -->
          <div class="settings-group">
            <div class="settings-group-title">{{ t('versionControl') }}</div>
            <div class="settings-row">
              <label class="settings-label">{{ t('gitPath') }}</label>
              <input v-model="ideSettings.gitPath" class="settings-input" :placeholder="t('pathAutoHint')" />
              <span class="settings-hint" v-if="!ideSettings.gitPath">{{ t('notConfigured') }}</span>
            </div>
            <div class="settings-row">
              <label class="settings-label">{{ t('svnPath') }}</label>
              <input v-model="ideSettings.svnPath" class="settings-input" :placeholder="t('pathAutoHint')" />
              <span class="settings-hint" v-if="!ideSettings.svnPath">{{ t('notConfigured') }}</span>
            </div>
          </div>
          <!-- AI 助手 -->
          <div class="settings-group">
            <div class="settings-group-title">{{ t('aiAssistant') }}</div>
            <div class="settings-row">
              <label class="settings-label">{{ t('enableAi') }}</label>
              <select v-model="ideSettings.aiEnabled" class="settings-select">
                <option :value="true">{{ t('enabled') }}</option>
                <option :value="false">{{ t('disabled') }}</option>
              </select>
            </div>
            <!-- When the starter says manual AI config is not required (e.g. api/adapter
                 route AI through a remote/external connection), hide manual config fields -->
            <template v-if="ideSettings.aiConfigRequired">
              <div class="settings-row">
                <label class="settings-label">{{ t('aiApiUrl') }}</label>
                <input v-model="ideSettings.aiApiUrl" class="settings-input" :placeholder="t('aiApiUrlHint')" />
              </div>
              <div class="settings-row">
                <label class="settings-label">{{ t('apiToken') }}</label>
                <input v-model="ideSettings.aiApiToken" type="password" class="settings-input" />
              </div>
              <div class="settings-row">
                <label class="settings-label">{{ t('aiModel') }}</label>
                <input v-model="ideSettings.aiModel" class="settings-input" :placeholder="t('aiModelHintFull')" />
              </div>
            </template>
            <div v-else class="settings-row">
              <span class="settings-mode-hint">{{ t('aiModeHint', { mode: ideSettings.mode }) }}</span>
            </div>
          </div>
        </div>
        <div class="settings-footer">
          <button class="dialog-btn" @click="showSettingsPanel = false">{{ t('cancel') }}</button>
          <button class="dialog-btn primary" @click="saveSettings">{{ t('saveBtn') }}</button>
        </div>
      </div>
    </div>

    <!-- Decompile Class Browser Dialog -->
    <div v-if="decompileDialog.visible" class="dialog-overlay" @click="decompileDialog.visible = false">
      <div class="dialog-box decompile-dialog" @click.stop>
        <div class="dialog-title">📂 {{ decompileDialog.jarName }} — {{ t('decompileBrowseClasses') }}</div>
        <input class="dialog-input" v-model="decompileDialog.filter" :placeholder="t('decompileSearch')" />
        <div class="decompile-class-list" v-if="!decompileDialog.loading">
          <div v-for="cls in filteredDecompileClasses" :key="cls"
               class="decompile-class-item"
               :class="{ decompiling: decompileDialog.decompiling === cls }"
               @click="doDecompileClass(cls)">
            <span class="decompile-class-icon">{{ decompileDialog.decompiling === cls ? '⏳' : '☕' }}</span>
            <span class="decompile-class-name">{{ cls }}</span>
          </div>
          <div v-if="filteredDecompileClasses.length === 0" class="decompile-empty">
            {{ t('decompileNoClasses') }}
          </div>
        </div>
        <div v-else class="decompile-loading">{{ t('decompileLoading') }}</div>
        <div class="dialog-actions">
          <button class="dialog-btn" @click="decompileDialog.visible = false">{{ t('close') }}</button>
        </div>
      </div>
    </div>

    <!-- Search Dialog (Ctrl+P / Ctrl+T) — VS Code style, no backdrop -->
    <div v-if="searchVisible" class="search-panel-wrapper" @keydown.esc="searchVisible = false">
      <div class="search-panel">
        <div class="search-input-row">
          <span class="search-icon">🔍</span>
          <input class="search-input" ref="searchInput" v-model="searchQuery"
                 @input="onSearchInput" @keydown="onSearchKeydown"
                 :placeholder="searchPlaceholder" />
          <span class="search-project-tag" v-if="activeProject">📦 {{ activeProject }}</span>
          <span class="search-close-btn" @click="searchVisible = false">✕</span>
        </div>
        <div class="search-filter-row">
          <div class="search-type-tabs">
            <span class="search-type-tab" :class="{ active: searchType === 'all' }" @click="setSearchType('all')">{{ t('allType') }}</span>
            <span class="search-type-tab" :class="{ active: searchType === 'file' }" @click="setSearchType('file')">{{ t('fileType') }}</span>
            <span class="search-type-tab" :class="{ active: searchType === 'symbol' }" @click="setSearchType('symbol')">{{ t('symbolType') }}</span>
          </div>
          <div class="search-ext-bar">
            <span class="search-ext-chip" :class="{ active: searchExt === '' }" @click="setSearchExt('')">*.*</span>
            <span class="search-ext-chip" :class="{ active: searchExt === 'java' }" @click="setSearchExt('java')">{{ t('extJava') }}</span>
            <span class="search-ext-chip" :class="{ active: searchExt === 'xml' }" @click="setSearchExt('xml')">{{ t('extXml') }}</span>
            <span class="search-ext-chip" :class="{ active: searchExt === 'html,jsp' }" @click="setSearchExt('html,jsp')">{{ t('extHtml') }}</span>
            <span class="search-ext-chip" :class="{ active: searchExt === 'js,ts' }" @click="setSearchExt('js,ts')">{{ t('extJs') }}</span>
            <span class="search-ext-chip" :class="{ active: searchExt === 'properties,yml,yaml' }" @click="setSearchExt('properties,yml,yaml')">{{ t('config') }}</span>
            <input class="search-ext-input" v-model="searchExtCustom" @input="onExtCustomInput"
                   :placeholder="t('customExt')" :title="t('extFilterHint')" />
          </div>
        </div>
        <div class="search-results" ref="searchResultsEl">
          <div v-if="searchLoading" class="search-empty">{{ t('searching') }}</div>
          <div v-else-if="searchResults.length === 0 && searchQuery.trim()" class="search-empty">{{ t('noMatch') }}</div>
          <div v-else-if="searchResults.length === 0" class="search-empty">{{ t('typeToSearch') }}</div>
          <div v-for="(item, idx) in searchResults" :key="idx"
               class="search-result-item" :class="{ selected: searchSelectedIdx === idx }"
               @click="openSearchResult(item)" @mouseenter="searchSelectedIdx = idx">
            <span class="search-result-icon">{{ item.icon }}</span>
            <div class="search-result-info">
              <span class="search-result-name">{{ item.name }}</span>
              <span class="search-result-detail" v-if="item.detail">{{ item.detail }}</span>
            </div>
            <span class="search-result-path">{{ item.path }}<template v-if="item.line > 0">:{{ item.line }}</template></span>
            <span class="search-result-badge" :class="item.type">{{ item.type === 'file' ? t('fileType') : t('symbolType') }}</span>
          </div>
        </div>
      </div>
      <div class="search-backdrop" @click="searchVisible = false"></div>
    </div>

    <!-- Status Bar -->
    <div class="statusbar">
      <span class="status-item">{{ lspStatus }}</span>
      <span class="status-sep">|</span>
      <span class="status-item debug-status-badge" :class="debugState">
        <template v-if="debugState === 'suspended'">⏸ {{ t('debugSuspended') }}</template>
        <template v-else-if="debugState === 'running'">▶ {{ t('debugRunning') }}</template>
        <template v-else-if="debugState === 'disconnected' && debugReconnectAvailable">🔌 {{ t('debugRunning') }} ({{ t('debugReconnect') }})</template>
        <template v-else-if="debugState === 'disconnected'">{{ t('debugDisconnected') }}</template>
      </span>
      <span class="status-sep">|</span>
      <span class="status-item">Java {{ ideSettings.jdkVersion }}</span>
      <span class="status-sep">|</span>
      <span class="status-item">UTF-8</span>
      <span class="status-spacer"></span>
      <span class="status-item" v-if="activeProject">📦 {{ activeProject }}</span>
      <span class="status-sep" v-if="deployRunning || deployReconnecting">|</span>
      <span class="status-item" v-if="deployReconnecting">🔌 {{ t('deployRunning') }}</span>
      <span class="status-sep" v-if="activeProject">|</span>
      <span class="status-item">Ln {{ cursorLine }}, Col {{ cursorCol }}</span>
    </div>
  </div>
</template>

<script>
const nodeIcon = (node, expanded) => {
  if (node.type === 'directory') {
    if (node.nodeKind === 'package') return expanded ? '📂' : '📦'
    return expanded ? '📂' : '📁'
  }
  const k = node.nodeKind
  if (k === 'java') return '☕'
  if (k === 'jar') return '🫙'
  if (k === 'xml') return '📋'
  if (k === 'gradle') return '🐘'
  return '📄'
}

const TreeNodeItem = {
  name: 'TreeNodeItem',
  props: { node: Object, depth: Number, project: String, activeFile: String, revealPath: String },
  emits: ['select-file', 'ctx-menu'],
  data() { return { expanded: false } },
  computed: {
    paddingLeft() { return (this.depth * 16 + 8) + 'px' },
    filePath() { return this.node._path || this.node.name },
    fileKey() { return this.project + ':' + this.filePath },
    isActive() { return this.activeFile === this.fileKey },
    icon() { return nodeIcon(this.node, this.expanded) },
    /** 判断当前需要 reveal 的路径是否在本节点的子树中 */
    shouldExpand() {
      if (!this.revealPath || this.node.type !== 'directory') return false
      const prefix = this.project + ':' + this.filePath + '/'
      return this.revealPath.startsWith(prefix)
    }
  },
  watch: {
    /** 当 revealPath 变化时，如果目标在子树中则自动展开 */
    shouldExpand: {
      immediate: true,
      handler(val) {
        if (val) this.expanded = true
      }
    },
    /** 当本节点就是目标文件时，滚动到可见区域 */
    isActive(val) {
      if (val) {
        this.$nextTick(() => {
          if (this.$el) {
            const nodeEl = this.$el.querySelector ? this.$el.querySelector('.tree-node.active') : this.$el
            if (nodeEl && nodeEl.scrollIntoViewIfNeeded) nodeEl.scrollIntoViewIfNeeded({ block: 'nearest' })
            else if (nodeEl && nodeEl.scrollIntoView) nodeEl.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
          }
        })
      }
    }
  },
  methods: {
    toggle() {
      if (this.node.type === 'directory') { this.expanded = !this.expanded }
      else { this.$emit('select-file', { project: this.project, path: this.filePath, name: this.node.name }) }
    },
    onCtx(e) {
      const kind = this.node.type === 'directory'
        ? (this.node.nodeKind === 'package' ? 'package' : 'folder')
        : 'file'
      this.$emit('ctx-menu', { event: e, project: this.project, path: this.filePath, kind, nodeName: this.node.name })
    }
  },
  template: `
    <div>
      <div class="tree-node" :class="{ active: isActive }" :style="{ paddingLeft }"
           @click="toggle" @contextmenu.prevent="onCtx">
        <span class="icon">{{ icon }}</span>
        <span class="label">{{ node.name }}</span>
      </div>
      <template v-if="expanded && node.children">
        <tree-node-item v-for="child in node.children" :key="filePath + '/' + child.name"
          :node="child" :depth="depth + 1" :project="project" :active-file="activeFile"
          :reveal-path="revealPath"
          @select-file="$emit('select-file', $event)"
          @ctx-menu="$emit('ctx-menu', $event)" />
      </template>
    </div>
  `
}
export default { components: { TreeNodeItem } }
</script>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as monaco from 'monaco-editor'
import EditorWorker from 'monaco-editor/editor/editor.worker?worker'
// Monaco 0.56 restructured basic-languages into a single contribution that
// registers every tokenizer. The old per-language paths
// (`monaco-editor/esm/vs/basic-languages/{css,html}/...`) no longer exist, and
// 0.56's package.json `exports` map remaps the old `esm/vs/...` subpaths, so
// they would fail to resolve at build time.
import 'monaco-editor/basic-languages/monaco.contribution'
// Monaco >=0.44 loads its workers as ES modules (`new Worker(url, { type:
// 'module' })`). The previous `vite-plugin-monaco-editor` bundled the editor
// worker as a classic IIFE, which fails that bootstrap at runtime (Monaco then
// falls back to running worker code on the main thread). The modern, supported
// approach is `MonacoEnvironment.getWorker` returning a Vite `?worker` import.
window.MonacoEnvironment = {
  getWorker() {
    return new EditorWorker()
  },
}
import http from './utils/http'
import { listen } from 'vscode-ws-jsonrpc'
import { t, setLang, getLang, getAvailableLangs, currentLang, onLangChange } from './i18n.js'
import XtermTerminal from './components/XtermTerminal.vue'
import VcsSidebar from './components/VcsSidebar.vue'
import VcsCommitHistory from './components/VcsCommitHistory.vue'

// ==================== Auth ====================
const authToken = ref('')
const authUser = ref('')
const showLogin = ref(true)
const loginForm = reactive({ username: '', password: '', error: '', loading: false })
const kickAlert = reactive({ visible: false, requestId: '', newUser: '' })
let kickPollTimer = null

// 给所有 axios 请求加上 token header 和语言 header
http.interceptors.request.use(config => {
  if (authToken.value) config.headers['X-Auth-Token'] = authToken.value
  // 添加语言 header，供后端 i18n 使用
  const lang = localStorage.getItem('ide-lang') || ideSettings.language || 'en'
  config.headers['X-Lang'] = lang
  return config
})
// 401 响应自动弹出登录
http.interceptors.response.use(res => res, err => {
  if (err.response && err.response.status === 401) {
    showLogin.value = true
    authToken.value = ''
  }
  return Promise.reject(err)
})

const DEFAULT_CODE = ''
const DOC_URI = 'file:///workspace/Main.java'

/**
 * 从文件路径和源码推断主类全限定名
 * 例如 src/main/java/com/example/Main.java → com.example.Main
 * 或 src/Hello.java → Hello
 */
function guessMainClass(filePath, source) {
  // 先从源码中提取 package 声明
  const pkgMatch = source && source.match(/^\s*package\s+([\w.]+)\s*;/m)
  const classMatch = source && source.match(/public\s+class\s+(\w+)/)
  const className = classMatch ? classMatch[1] : filePath.split('/').pop().replace('.java', '')
  if (pkgMatch) return pkgMatch[1] + '.' + className
  // 从路径推断
  const srcRoots = ['src/main/java/', 'src/test/java/', 'src/']
  for (const root of srcRoots) {
    const idx = filePath.indexOf(root)
    if (idx >= 0) {
      const rel = filePath.substring(idx + root.length).replace('.java', '').replace(/\//g, '.')
      if (rel) return rel
    }
  }
  return className
}

function getLanguageByFileName(name) {
  if (!name) return 'plaintext'
  const ext = name.split('.').pop().toLowerCase()
  const map = {
    java: 'java', xml: 'xml', json: 'json', yml: 'yaml', yaml: 'yaml',
    properties: 'ini', md: 'markdown', html: 'html', css: 'css',
    js: 'javascript', ts: 'typescript', gradle: 'groovy', kt: 'kotlin',
    py: 'python', sh: 'shell', bat: 'bat', sql: 'sql', txt: 'plaintext',
    vue: 'html', svelte: 'html', jsx: 'javascript', tsx: 'typescript'
  }
  return map[ext] || 'plaintext'
}

function isCurrentTabJava() {
  if (!currentTab.value) return false
  const tab = openTabs.value.find(t => t.key === currentTab.value)
  return tab && tab.label.endsWith('.java')
}

/** 根据当前 tab 生成项目感知的 URI: file:///workspace/{project}/{path} */
function getCurrentDocUri() {
  if (!currentTab.value) return DOC_URI
  const tab = openTabs.value.find(t => t.key === currentTab.value)
  if (!tab) return DOC_URI
  return 'file:///workspace/' + tab.project + '/' + tab.path
}

/** 获取 URI 对应的语言标识 */
function getLanguageByUri(uri) {
  const name = uri.split('/').pop() || uri
  return getLanguageByFileName(name)
}

/** 确保指定 URI 的 Monaco model 存在，不存在则从后端加载 */
async function ensureModelExists(uriStr, project, filePath) {
  const uri = monaco.Uri.parse(uriStr)
  if (monaco.editor.getModel(uri)) return true
  try {
    console.log('[ensureModelExists] loading model for', uriStr)
    const { data } = await http.get(`/workspace/projects/${project}/file`, { params: { path: filePath } })
    if (data && data.success) {
      monaco.editor.createModel(data.content || '', getLanguageByUri(filePath), uri)
      console.log('[ensureModelExists] model created for', uriStr)
      return true
    }
    console.warn('[ensureModelExists] failed to load model for', uriStr, 'data:', data)
  } catch (e) {
    console.warn('[ensureModelExists] error loading model for', uriStr, e)
  }
  return false
}

function revealEditorTarget(line, column = 1) {
  if (!line || !editor) return
  editor.revealLineInCenter(line)
  editor.setPosition({ lineNumber: line, column: column || 1 })
  editor.focus()
}

/**
 * Parse a file:///workspace/{project}/{filePath} URI and navigate to it.
 * Called from both the openCodeEditor interceptor and the setModel fallback.
 * Returns true if handled.
 */
function handleWorkspaceNavigation(uriStr, line, column = 1) {
  if (!uriStr || !uriStr.startsWith('file:///workspace/')) return false
  const relativePath = uriStr.replace('file:///workspace/', '')
  const projectEnd = relativePath.indexOf('/')
  if (projectEnd <= 0) return false
  const project = relativePath.substring(0, projectEnd)
  const filePath = relativePath.substring(projectEnd + 1)
  const fileName = filePath.substring(filePath.lastIndexOf('/') + 1) || filePath
  const key = project + ':' + filePath

  const existing = openTabs.value.find(t => t.key === key)
  if (existing) {
    if (key !== currentTab.value) switchTab(key)
    nextTick().then(() => revealEditorTarget(line, column))
    return true
  }

  onSelectFile({ project, path: filePath, name: fileName }).then(() => {
    nextTick().then(() => revealEditorTarget(line, column))
  }).catch(e => {
    console.warn('[workspaceNav] failed to open file:', key, e)
  })
  return true
}

function getOpenCodeEditorTarget(args) {
  const positionalResource = args[1]
  const positionalSelection = args[2]
  const input = args[0]
  const candidates = [
    { resource: input?.resource, selection: input?.options?.selection || input?.selection },
    { resource: input?.original?.resource, selection: input?.original?.options?.selection || input?.options?.selection },
    { resource: input?.modified?.resource, selection: input?.modified?.options?.selection || input?.options?.selection },
    { resource: positionalResource, selection: positionalSelection },
  ]
  const target = candidates
    .map(candidate => ({ ...candidate, uriStr: candidate.resource ? candidate.resource.toString() : '' }))
    .find(candidate => candidate.uriStr.includes('://'))
  if (!target) return { uriStr: '', line: null, column: 1 }
  const selection = target.selection
  return {
    uriStr: target.uriStr,
    line: selection?.startLineNumber || selection?.selectionStartLineNumber || selection?.lineNumber || null,
    column: selection?.startColumn || selection?.selectionStartColumn || selection?.column || 1,
  }
}

/**
 * Try to intercept Monaco's internal code editor service to handle peek widget navigation.
 * Uses multiple approaches for different Monaco versions.
 */
function setupPeekNavigation() {
  let codeEditorService = null
  try {
    codeEditorService = editor._codeEditorService
    console.log('[setupPeekNavigation] _codeEditorService:', typeof codeEditorService, codeEditorService ? '(found)' : '(not found)')
  } catch (e) {
    console.log('[setupPeekNavigation] _codeEditorService access error:', e)
  }
  if (!codeEditorService) {
    console.log('[setupPeekNavigation] _codeEditorService not available, skipping peek navigation intercept')
  }
  if (codeEditorService && codeEditorService.openCodeEditor) {
    console.log('[setupPeekNavigation] using openCodeEditor interceptor')
    const originalOpen = codeEditorService.openCodeEditor.bind(codeEditorService)
    codeEditorService.openCodeEditor = async (...args) => {
      const { uriStr, line, column } = getOpenCodeEditorTarget(args)
      console.log('[openCodeEditor] called, uri:', uriStr, 'selection:', line ? { line, col: column } : 'none')
      if (uriStr.startsWith('file:///workspace/')) {
        const handled = handleWorkspaceNavigation(uriStr, line, column)
        if (handled) return true
      }
      return originalOpen(...args)
    }
  } else {
    // Fallback: intercept editor.setModel to detect peek/dead-click navigation and open files
    console.log('[setupPeekNavigation] using setModel fallback (codeEditorService unavailable)')
    try {
      const origSetModel = editor.setModel.bind(editor)
      editor.setModel = (model) => {
        const result = origSetModel(model)
        if (model) {
          const uri = model.uri.toString()
          console.log('[setModel] switched to:', uri)
          handleWorkspaceNavigation(uri, null)
        }
        return result
      }
    } catch (e) {
      console.warn('[setupPeekNavigation] fallback failed:', e)
    }
  }
}
const consoleBody = ref(null)
const dialogInput = ref(null)
const jarFileInput = ref(null)
const editorContainer = ref(null)
let editor = null
let lspWebSocket = null
let lspConnection = null
let docVersion = 1

const loading = ref(false)
const runConfig = reactive({ mainClass: '', programArgs: '', jvmArgs: '', timeLimit: '' })
const showRunConfigPanel = ref(false)
const detectedMainClasses = ref([])
const consoleTab = ref('console')
const consoleLines = ref([])
const problems = ref([])
const lspStatus = ref(t('lspConnecting'))
const cursorLine = ref(1)
const cursorCol = ref(1)
const sidebarWidth = ref(260)
const consoleHeight = ref(200)

// Debug state
const debugState = ref('disconnected') // disconnected | running | suspended
const debugReconnectAvailable = ref(false) // true when backend has running session but we're disconnected

// Deploy reconnect state
const deployReconnecting = ref(false)
const debugThreadId = ref(0)
const debugStackFrames = ref([])
const debugVariables = ref([])
const debugBreakpointLines = ref(new Map()) // "filepath:lineNumber" -> { decorations, bpId, filePath, className, fileName, lineNumber, enabled }
let stepPending = false // prevents rapid step clicks during transition
const debugBreakpointList = computed(() => {
  const list = []
  debugBreakpointLines.value.forEach((entry, key) => {
    list.push({
      id: entry.bpId || key,
      key: key,
      filePath: entry.filePath || '',
      className: entry.className || '',
      fileName: entry.fileName || '',
      lineNumber: entry.lineNumber || 0,
      enabled: entry.enabled !== false,
      decorations: entry.decorations
    })
  })
  return list.sort((a, b) => (a.filePath || '').localeCompare(b.filePath || '') || a.lineNumber - b.lineNumber)
})
const debugShowTab = computed(() => debugState.value !== 'disconnected' || debugBreakpointList.value.length > 0)
let debugSSEController = null
let debugDecorations = []

// Decompile state
const decompileDialog = reactive({
  visible: false,
  project: '',
  jarPath: '',
  jarName: '',
  loading: false,
  classes: [],
  filter: '',
  decompiling: null // className being decompiled
})

// ==================== Right Panel (Maven/Gradle Tool Window) ====================
const rightPanel = ref('')  // '' | 'maven' | 'gradle' | 'vcs'
const rightPanelWidth = ref(280)
const mavenLifecycle = ['clean', 'validate', 'compile', 'test', 'package', 'verify', 'install', 'site', 'deploy']
const mavenPlugins = ref([])
const mavenMainClasses = ref([])
const mavenDeps = ref([])
const mvnExpanded = reactive({ root: true, lifecycle: true, plugins: false, runConfigs: false, deps: false })
const gradleTasks = ref(['build', 'clean', 'test', 'jar', 'classes', 'compileJava', 'processResources', 'check', 'assemble'])
const gradleMainClasses = ref([])
const gradleDeps = ref([])
const gradleExpanded = reactive({ root: true, tasks: true, runConfigs: false, deps: false })

// ==================== Theme & Settings ====================
const isDark = ref(true)
const showSettingsPanel = ref(false)
const jdkVersions = Array.from({ length: 21 }, (_, i) => i + 5) // 5..25
const ideSettings = reactive({
  theme: 'dark',
  language: 'en',
  jdkVersion: 25,
  javaHome: '',
  mavenHome: '',
  mavenUserSettings: '',
  mavenLocalRepository: '',
  gradleUserHome: '',
  gitPath: '',
  svnPath: '',
  aiApiUrl: '',
  aiApiToken: '',
  aiModel: '',
  aiEnabled: false,
  mode: 'standard',
  // Whether the IDE needs to maintain manual AI URL/key/model config.
  // Provided by the starter (IdeModeCapabilities.requiresManualAiConfig()).
  aiConfigRequired: true
})
const themeClass = computed(() => isDark.value ? 'theme-dark' : 'theme-light')
const activeProjectType = computed(() => {
  if (!activeProject.value) return 'plain'
  const p = projects.value.find(x => x.name === activeProject.value)
  return p ? p.type : 'plain'
})
const activeProjectVcsType = computed(() => {
  if (!activeProject.value) return 'none'
  const p = projects.value.find(x => x.name === activeProject.value)
  return p ? (p.vcsType || 'none') : 'none'
})
const filteredBranches = computed(() => {
  const q = branchDialog.filter.trim().toLowerCase()
  if (!q) return branchDialog.branches
  return branchDialog.branches.filter(b => b.toLowerCase().includes(q))
})

const filteredDecompileClasses = computed(() => {
  const q = decompileDialog.filter.trim().toLowerCase()
  if (!q) return decompileDialog.classes
  return decompileDialog.classes.filter(c => c.toLowerCase().includes(q))
})

// ==================== Auto Save ====================
const dirtyTabs = ref({}) // key -> boolean, 标记未保存的 tab
let autoSaveTimer = null
let autoCompileTimer = null

/** 静默保存指定 tab 到后端（不打印控制台消息） */
async function silentSave(tabKey) {
  const tab = openTabs.value.find(t => t.key === tabKey)
  if (!tab) return
  const content = tabKey === currentTab.value && editor ? editor.getValue() : tabContents.value[tabKey]
  if (content === undefined) return
  try {
    await http.put(`/workspace/projects/${tab.project}/file`, { path: tab.path, content })
    tabContents.value = { ...tabContents.value, [tabKey]: content }
    dirtyTabs.value = { ...dirtyTabs.value, [tabKey]: false }
    // Schedule auto-compile for Java files
    if (tab.path && tab.path.endsWith('.java')) {
      scheduleAutoCompile(tab)
    }
  } catch (e) {
    console.warn('Auto-save failed:', tabKey, e.message)
  }
}

/** 防抖自动保存：内容变化后 1 秒无操作则保存 */
function scheduleAutoSave() {
  if (!currentTab.value) return
  dirtyTabs.value = { ...dirtyTabs.value, [currentTab.value]: true }
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    if (currentTab.value) silentSave(currentTab.value)
  }, 1000)
}

/** 立即保存当前 tab（用于切换/关闭前） */
async function flushSave() {
  if (autoSaveTimer) { clearTimeout(autoSaveTimer); autoSaveTimer = null }
  if (currentTab.value && dirtyTabs.value[currentTab.value]) {
    await silentSave(currentTab.value)
  }
}

// ==================== Auto Compile ====================

/** 防抖自动编译：保存后 3 秒无新保存则编译 */
function scheduleAutoCompile(tab) {
  if (autoCompileTimer) clearTimeout(autoCompileTimer)
  autoCompileTimer = setTimeout(() => {
    silentCompile(tab)
  }, 3000)
}

/** 静默编译项目（保存后自动触发） */
async function silentCompile(tab) {
  if (!tab || !tab.project) return
  try {
    const { data } = await http.post(`/workspace/projects/${tab.project}/compile`)
    if (data && !data.success && data.output) {
      const errSummary = data.output.split('\n')
        .filter(l => l.includes('ERROR') || l.includes('error:'))
        .slice(0, 5).join('\n')
      if (errSummary) {
        appendConsole('> ⚠️ ' + t('compileErrors') + ':\n' + errSummary, 'stderr')
      }
    }
  } catch (e) {
    // Silently ignore compile errors during auto-compile
  }
}

// ==================== Package Explorer State ====================
const projects = ref([])
const activeProject = ref('')
const expandedProjects = ref({})
const projectTrees = ref({})
const treeVersion = ref(0)
const expandedLibs = ref({})
const projectLibs = ref({})
const activeFile = ref('')
const revealPath = ref('')  // 需要在树中定位的文件 key
const openTabs = ref([])
const currentTab = ref('')
const tabContents = ref({})

// Context menu state
const ctxMenu = reactive({ visible: false, x: 0, y: 0, project: '', path: '', kind: '', nodeName: '' })

// Tab context menu state
const tabCtxMenu = reactive({ visible: false, x: 0, y: 0, tabKey: '' })

// Dialog state
const dialog = reactive({ visible: false, title: '', value: '', placeholder: '', onConfirm: () => {} })

// New Project Dialog state
const newProjectDialog = reactive({ visible: false, name: '' })

// Branch dialog state
const branchDialog = reactive({ visible: false, branches: [], currentBranch: '', newBranchName: '', loading: false, filter: '' })




// ==================== Projects ====================
// ==================== 请求防抖锁 ====================
/** 正在进行中的请求 key 集合，防止连续点击重复请求 */
const pendingRequests = new Set()

async function loadProjects() {
  console.log('[loadProjects] called, pending:', pendingRequests.has('loadProjects'))
  if (pendingRequests.has('loadProjects')) return
  pendingRequests.add('loadProjects')
  try {
    const { data } = await http.get('/workspace/projects')
    console.log('[loadProjects] success:', data.projects)
    projects.value = data.projects || []
  } catch (e) { console.error('[loadProjects] failed', e) }
  finally { pendingRequests.delete('loadProjects') }
}

async function selectProject(name) {
  activeProject.value = name
  if (!expandedProjects.value[name]) await toggleProjectExpand(name)
}

async function toggleProjectExpand(name) {
  if (expandedProjects.value[name]) {
    expandedProjects.value = { ...expandedProjects.value, [name]: false }
    return
  }
  const reqKey = 'tree:' + name
  if (pendingRequests.has(reqKey)) return
  pendingRequests.add(reqKey)
  try {
    const { data } = await http.get(`/workspace/projects/${name}/tree`)
    if (data.success && data.tree && data.tree.children) {
      annotatePaths(data.tree.children, '')
      projectTrees.value = { ...projectTrees.value, [name]: data.tree.children }
    }
    expandedProjects.value = { ...expandedProjects.value, [name]: true }
  } catch (e) { console.error('Failed to load tree', e) }
  finally { pendingRequests.delete(reqKey) }
}

async function toggleLibExpand(name) {
  if (expandedLibs.value[name]) {
    expandedLibs.value = { ...expandedLibs.value, [name]: false }
    return
  }
  await loadLibs(name)
  expandedLibs.value = { ...expandedLibs.value, [name]: true }
}

async function loadLibs(name) {
  const reqKey = 'libs:' + name
  if (pendingRequests.has(reqKey)) return
  pendingRequests.add(reqKey)
  try {
    const { data } = await http.get(`/workspace/projects/${name}/libs`)
    if (data.success) {
      projectLibs.value = { ...projectLibs.value, [name]: data }
    }
  } catch (e) { console.error('Failed to load libs', e) }
  finally { pendingRequests.delete(reqKey) }
}

function annotatePaths(nodes, parentPath) {
  for (const node of nodes) {
    node._path = parentPath ? parentPath + '/' + node.name : node.name
    if (node.children) annotatePaths(node.children, node._path)
  }
}

async function refreshTree(project) {
  const reqKey = 'refreshTree:' + project
  if (pendingRequests.has(reqKey)) return
  pendingRequests.add(reqKey)
  try {
    await loadProjects()
    const { data } = await http.get(`/workspace/projects/${project}/tree`)
    if (data.success && data.tree && data.tree.children) {
      annotatePaths(data.tree.children, '')
      projectTrees.value = { ...projectTrees.value, [project]: data.tree.children }
      treeVersion.value++
    }
    expandedProjects.value = { ...expandedProjects.value, [project]: true }
  } catch (e) { console.error('Failed to refresh tree', e) }
  finally { pendingRequests.delete(reqKey) }
  if (expandedLibs.value[project]) await loadLibs(project)
}

/** Refresh project: reload tree + update open tabs with external file changes */
async function doRefreshProject(project) {
  appendConsole('> 🔄 ' + t('refreshingProject', { project }) + '...', 'info')
  consoleTab.value = 'console'

  // 1. Refresh file tree
  if (expandedProjects.value[project]) {
    await refreshTree(project)
  } else {
    await loadProjects()
  }

  // 2. Reload open tabs for this project to reflect external changes
  const tabsToReload = openTabs.value.filter(t => t.project === project)
  for (const tab of tabsToReload) {
    try {
      const { data } = await http.get(`/workspace/projects/${tab.project}/file`, { params: { path: tab.path } })
        if (data.success) {
        const newContent = data.content
        const oldContent = tabContents.value[tab.key]
        tabContents.value = { ...tabContents.value, [tab.key]: newContent }
        // Update the tab ref content
        const idx = openTabs.value.findIndex(t => t.key === tab.key)
        if (idx >= 0) {
          openTabs.value[idx] = { ...openTabs.value[idx], content: newContent }
        }
        // Update editor if this is the active tab
        if (tab.key === currentTab.value && editor) {
          const model = editor.getModel()
          if (model && model.getValue() !== newContent) {
            model.setValue(newContent)
          }
        }
        // Remove dirty flag if content changed
        if (oldContent !== newContent && dirtyTabs.value[tab.key]) {
          dirtyTabs.value = { ...dirtyTabs.value, [tab.key]: false }
        }
      }
    } catch {
      // File may have been deleted externally — keep tab as-is
    }
  }

  appendConsole('> ✅ ' + t('projectRefreshed', { count: tabsToReload.length }), 'info')
}

// ==================== File/Tab Management ====================
async function onSelectFile({ project, path, name }) {
  const key = project + ':' + path
  activeProject.value = project
  const existing = openTabs.value.find(t => t.key === key)
  if (existing) {
    activeFile.value = key
    switchTab(key)
    return
  }
  const reqKey = 'file:' + key
  if (pendingRequests.has(reqKey)) return
  pendingRequests.add(reqKey)
  try {
    const { data } = await http.get(`/workspace/projects/${project}/file`, { params: { path } })
    if (data.success) {
      openTabs.value.push({ key, label: name, project, path, content: data.content })
      tabContents.value = { ...tabContents.value, [key]: data.content }
      activeFile.value = key
      switchTab(key)
    }
  } catch (e) { console.error('Failed to load file', e) }
  finally { pendingRequests.delete(reqKey) }
}

/** 记录已经向 LSP 服务端发送过 didOpen 的 URI 集合 */
const lspOpenedUris = new Set()

function switchTab(key) {
  if (currentTab.value && editor) {
    tabContents.value = { ...tabContents.value, [currentTab.value]: editor.getValue() }
    // 切换前立即保存当前 tab
    if (dirtyTabs.value[currentTab.value]) silentSave(currentTab.value)
  }
  if (autoSaveTimer) { clearTimeout(autoSaveTimer); autoSaveTimer = null }
  currentTab.value = key
  if (editor && tabContents.value[key] !== undefined) {
    // 关闭 peek widget（View Problem 弹窗）
    editor.trigger('switchTab', 'closeMarkersNavigation', {})
    const tab = openTabs.value.find(t => t.key === key)
    if (!tab) return
    const lang = getLanguageByFileName(tab.label)
    const uri = monaco.Uri.parse('file:///workspace/' + tab.project + '/' + tab.path)
    let model = monaco.editor.getModel(uri)
    if (!model) {
      model = monaco.editor.createModel(tabContents.value[key], lang, uri)
    } else if (model.getValue() !== tabContents.value[key]) {
      model.setValue(tabContents.value[key])
    }
    editor.setModel(model)
    // Restore breakpoint glyphs for this file
    restoreBreakpointGlyphs(tab.path)
    // 切换文件时先清除所有诊断标记
    monaco.editor.setModelMarkers(model, 'jdt-core', [])
    monaco.editor.setModelMarkers(model, 'custom-lint', [])
    // 清空 problems 面板
    problems.value = []
    // 只对 Java 文件发送 LSP 通知
    if (lang === 'java' && lspConnection) {
      const uriStr = getCurrentDocUri()
      docVersion++
      if (!lspOpenedUris.has(uriStr)) {
        // 首次打开该文件，发送 didOpen 通知
        lspOpenedUris.add(uriStr)
        lspConnection.sendNotification('textDocument/didOpen', {
          textDocument: { uri: uriStr, languageId: 'java', version: docVersion, text: model.getValue() }
        })
      } else {
        // 已经 didOpen 过，发送 didChange 通知
        lspConnection.sendNotification('textDocument/didChange', {
          textDocument: { uri: uriStr, version: docVersion },
          contentChanges: [{ text: model.getValue() }]
        })
      }
    }
    // 非 Java 文件运行自定义校验
    if (lang !== 'java') {
      validateCurrentFile()
    }
    // Toggle readOnly for decompiled (or other read-only) tabs
    if (editor) {
      editor.updateOptions({ readOnly: tab.readonly === true })
    }
  }
  activeFile.value = key
  // 在 Package Explorer 中定位到对应文件
  revealInTree(key)
}

function closeTab(key) {
  const idx = openTabs.value.findIndex(t => t.key === key)
  if (idx === -1) return
  // 关闭前保存
  if (dirtyTabs.value[key]) {
    if (key === currentTab.value && editor) {
      tabContents.value = { ...tabContents.value, [key]: editor.getValue() }
    }
    silentSave(key)
  }
  // 向 LSP 服务端发送 didClose 通知
  const tab = openTabs.value[idx]
  if (tab && lspConnection) {
    const tabLang = getLanguageByFileName(tab.label)
    if (tabLang === 'java') {
      const uri = 'file:///workspace/' + tab.project + '/' + tab.path
      lspConnection.sendNotification('textDocument/didClose', {
        textDocument: { uri }
      })
      lspOpenedUris.delete(uri)
    }
  }
  // 销毁对应的 Monaco model
  if (tab) {
    const uri = monaco.Uri.parse('file:///workspace/' + tab.project + '/' + tab.path)
    const model = monaco.editor.getModel(uri)
    if (model) model.dispose()
  }
  openTabs.value.splice(idx, 1)
  const newContents = { ...tabContents.value }
  delete newContents[key]
  tabContents.value = newContents
  const newDirty = { ...dirtyTabs.value }
  delete newDirty[key]
  dirtyTabs.value = newDirty
  if (currentTab.value === key) {
    if (openTabs.value.length > 0) {
      switchTab(openTabs.value[Math.min(idx, openTabs.value.length - 1)].key)
    } else {
      currentTab.value = ''; activeFile.value = ''
      // 恢复默认空模型
      const defaultModel = monaco.editor.getModel(monaco.Uri.parse(DOC_URI))
      if (defaultModel) {
        defaultModel.setValue(DEFAULT_CODE)
        editor.setModel(defaultModel)
      }
    }
  }
}

// ==================== Context Menu ====================
function showContextMenu(e, project, path, kind, nodeName) {
  ctxMenu.x = e.clientX; ctxMenu.y = e.clientY
  ctxMenu.project = project; ctxMenu.path = path; ctxMenu.kind = kind; ctxMenu.nodeName = nodeName
  ctxMenu.visible = true
}

function onTreeContextMenu({ event, project, path, kind, nodeName }) {
  showContextMenu(event, project, path, kind, nodeName)
}

function hideContextMenu() { ctxMenu.visible = false; tabCtxMenu.visible = false }

// ==================== Tab Context Menu ====================
function showTabContextMenu(e, tabKey) {
  tabCtxMenu.x = e.clientX; tabCtxMenu.y = e.clientY
  tabCtxMenu.tabKey = tabKey; tabCtxMenu.visible = true
  ctxMenu.visible = false
}

function tabCtxClose() {
  const key = tabCtxMenu.tabKey; tabCtxMenu.visible = false
  closeTab(key)
}

function tabCtxCloseOthers() {
  const key = tabCtxMenu.tabKey; tabCtxMenu.visible = false
  const toClose = openTabs.value.filter(t => t.key !== key).map(t => t.key)
  toClose.forEach(k => closeTab(k))
}

function tabCtxCloseLeft() {
  const key = tabCtxMenu.tabKey; tabCtxMenu.visible = false
  const idx = openTabs.value.findIndex(t => t.key === key)
  if (idx <= 0) return
  const toClose = openTabs.value.slice(0, idx).map(t => t.key)
  toClose.forEach(k => closeTab(k))
}

function tabCtxCloseRight() {
  const key = tabCtxMenu.tabKey; tabCtxMenu.visible = false
  const idx = openTabs.value.findIndex(t => t.key === key)
  if (idx < 0 || idx >= openTabs.value.length - 1) return
  const toClose = openTabs.value.slice(idx + 1).map(t => t.key)
  toClose.forEach(k => closeTab(k))
}

function tabCtxCloseAll() {
  tabCtxMenu.visible = false
  const toClose = openTabs.value.map(t => t.key)
  toClose.forEach(k => closeTab(k))
}

function openDialog(title, placeholder, defaultVal, onConfirm) {
  dialog.title = title; dialog.placeholder = placeholder; dialog.value = defaultVal || ''
  dialog.onConfirm = onConfirm; dialog.visible = true
  nextTick(() => { if (dialogInput.value) dialogInput.value.focus() })
}

// ---- Project-level context actions ----
const restoreDialog = reactive({ visible: false, loading: false, items: [] })

async function showRestoreProjectDialog() {
  restoreDialog.visible = true
  restoreDialog.loading = true
  restoreDialog.items = []
  try {
    const { data } = await http.get('/workspace/projects-removed')
    if (data.success) restoreDialog.items = data.removed || []
  } catch { alert(t('loadFailed')) }
  restoreDialog.loading = false
}

async function doRestoreProject(item) {
  try {
    const { data } = await http.post('/workspace/projects-restore', { dirName: item.dirName })
    if (data.success) {
      restoreDialog.items = restoreDialog.items.filter(i => i.dirName !== item.dirName)
      await loadProjects()
      if (data.name) await selectProject(data.name)
      if (restoreDialog.items.length === 0) restoreDialog.visible = false
    } else alert(data.message)
  } catch { alert(t('restoreFailed')) }
}

function showNewProjectDialog() {
  newProjectDialog.name = ''
  newProjectDialog.visible = true
}

async function doCreateProject() {
  const name = newProjectDialog.name.trim()
  if (!name) { alert(t('newProjectName') + ' required'); return }
  try {
    const { data } = await http.post('/workspace/projects', { name, type: 'simple' })
    if (data.success) {
      newProjectDialog.visible = false
      await loadProjects()
      await selectProject(data.name)
    } else alert(data.message)
  } catch { alert(t('createFailed')) }
}

function ctxRenameProject() {
  const old = ctxMenu.project; hideContextMenu()
  openDialog(t('renameProject'), t('enterNewName'), old, async () => {
    if (!dialog.value.trim() || dialog.value.trim() === old) { dialog.visible = false; return }
    try {
      const { data } = await http.put(`/workspace/projects/${old}`, { newName: dialog.value.trim() })
        if (data.success) {
        dialog.visible = false
        if (activeProject.value === old) activeProject.value = dialog.value.trim()
        await loadProjects()
      } else alert(data.message)
    } catch { alert(t('renameFailed')) }
  })
}

async function ctxRemoveProject() {
  const name = ctxMenu.project; hideContextMenu()
  if (!confirm(t('confirmRemove', { name }))) return
  try {
    const { data } = await http.delete(`/workspace/projects/${name}`, { params: { mode: 'remove' } })
    if (data.success) { cleanupProjectState(name); await loadProjects() } else alert(data.message)
  } catch { alert(t('removeFailed')) }
}

async function ctxDeleteProject() {
  const name = ctxMenu.project; hideContextMenu()
  if (!confirm(t('confirmPhysicalDelete', { name }))) return
  try {
    const { data } = await http.delete(`/workspace/projects/${name}`, { params: { mode: 'delete' } })
    if (data.success) { cleanupProjectState(name); await loadProjects() } else alert(data.message)
  } catch { alert(t('deleteFailed')) }
}

function cleanupProjectState(name) {
  if (activeProject.value === name) activeProject.value = ''
  const ep = { ...expandedProjects.value }; delete ep[name]; expandedProjects.value = ep
  const pt = { ...projectTrees.value }; delete pt[name]; projectTrees.value = pt
  const el = { ...expandedLibs.value }; delete el[name]; expandedLibs.value = el
  const pl = { ...projectLibs.value }; delete pl[name]; projectLibs.value = pl
  const newContents = { ...tabContents.value }
  openTabs.value = openTabs.value.filter(t => {
    if (t.project === name) { delete newContents[t.key]; return false }
    return true
  })
  tabContents.value = newContents
  if (currentTab.value.startsWith(name + ':')) {
    currentTab.value = openTabs.value.length > 0 ? openTabs.value[0].key : ''
    if (currentTab.value && editor) editor.setValue(tabContents.value[currentTab.value] || DEFAULT_CODE)
    else if (editor) editor.setValue(DEFAULT_CODE)
  }
}

// ---- File/Dir/Package context actions ----
function ctxNewFile() {
  const proj = ctxMenu.project
  const parentPath = ctxMenu.kind === 'project' ? '' : ctxMenu.path
  hideContextMenu()
  openDialog(t('newFile'), t('enterFileName'), '', async () => {
    if (!dialog.value.trim()) return
    try {
      const { data } = await http.post(`/workspace/projects/${proj}/file`, { parentPath, fileName: dialog.value.trim() })
        if (data.success) {
        dialog.visible = false
        await refreshTree(proj)
        // 自动打开新建的文件
        if (data.path && data.fileName) {
          await onSelectFile({ project: proj, path: data.path, name: data.fileName })
        }
      }
      else alert(data.message)
    } catch { alert(t('createFailed')) }
  })
}

function ctxNewPackage() {
  const proj = ctxMenu.project
  const parentPath = ctxMenu.kind === 'project' ? 'src' : ctxMenu.path
  hideContextMenu()
  openDialog(t('newPackage'), t('enterPackageName'), '', async () => {
    if (!dialog.value.trim()) return
    try {
      const { data } = await http.post(`/workspace/projects/${proj}/package`, { parentPath, packageName: dialog.value.trim() })
        if (data.success) { dialog.visible = false; await refreshTree(proj) }
      else alert(data.message)
    } catch { alert(t('createFailed')) }
  })
}

function ctxNewDir() {
  const proj = ctxMenu.project
  const parentPath = ctxMenu.kind === 'project' ? '' : ctxMenu.path
  hideContextMenu()
  openDialog(t('newSubDir'), t('enterDirName'), '', async () => {
    if (!dialog.value.trim()) return
    try {
      const { data } = await http.post(`/workspace/projects/${proj}/directory`, { parentPath, dirName: dialog.value.trim() })
        if (data.success) { dialog.visible = false; await refreshTree(proj) }
      else alert(data.message)
    } catch { alert(t('createFailed')) }
  })
}

function ctxRename() {
  const proj = ctxMenu.project; const path = ctxMenu.path; const oldName = ctxMenu.nodeName
  hideContextMenu()
  openDialog(t("rename"), t('enterNewName'), oldName, async () => {
    if (!dialog.value.trim() || dialog.value.trim() === oldName) { dialog.visible = false; return }
    try {
      const { data } = await http.put(`/workspace/projects/${proj}/rename`, { path, newName: dialog.value.trim() })
        if (data.success) { dialog.visible = false; await refreshTree(proj) }
      else alert(data.message)
    } catch { alert(t('renameFailed')) }
  })
}

async function ctxDelete() {
  const proj = ctxMenu.project; const path = ctxMenu.path; const nodeName = ctxMenu.nodeName
  hideContextMenu()
  if (!confirm(t('confirmDelete', { name: nodeName }))) return
  try {
    const { data } = await http.delete(`/workspace/projects/${proj}/path`, { params: { path } })
    if (data.success) {
      const prefix = proj + ':' + path
      const newContents = { ...tabContents.value }
      openTabs.value = openTabs.value.filter(t => {
        if (t.key === prefix || t.key.startsWith(prefix + '/')) { delete newContents[t.key]; return false }
        return true
      })
      tabContents.value = newContents
      if (currentTab.value === prefix || currentTab.value.startsWith(prefix + '/')) {
        currentTab.value = openTabs.value.length > 0 ? openTabs.value[0].key : ''
        if (currentTab.value && editor) editor.setValue(tabContents.value[currentTab.value] || DEFAULT_CODE)
        else if (editor) editor.setValue(DEFAULT_CODE)
      }
      await refreshTree(proj)
    } else alert(data.message)
  } catch { alert(t('deleteFailed')) }
}

// ---- JAR management ----
function ctxUploadJar() {
  hideContextMenu()
  if (jarFileInput.value) jarFileInput.value.click()
}

function ctxGitOpen() {
  const proj = ctxMenu.project
  hideContextMenu()
  activeProject.value = proj
  rightPanel.value = 'vcs'
  loadVcsInfo()
}

async function onJarFileSelected(e) {
  const file = e.target.files[0]
  if (!file) return
  const proj = ctxMenu.project || activeProject.value
  if (!proj) { alert(t('selectProject')); return }
  const formData = new FormData()
  formData.append('file', file)
  try {
    const { data } = await http.post(`/workspace/projects/${proj}/libs`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    })
    if (data.success) {
      await loadLibs(proj)
      expandedLibs.value = { ...expandedLibs.value, [proj]: true }
      await refreshTree(proj)
    } else alert(data.message)
  } catch { alert(t('uploadFailed')) }
  e.target.value = ''
}

/** 右键菜单：运行当前文件 main 方法 */
async function ctxRunMain() {
  const proj = ctxMenu.project
  const path = ctxMenu.path
  hideContextMenu()
  // 先打开文件（如果未打开）
  const key = proj + ':' + path
  const existing = openTabs.value.find(t => t.key === key)
  if (!existing) {
    try {
      const { data } = await http.get(`/workspace/projects/${proj}/file`, { params: { path } })
        if (data.success) {
        const name = path.split('/').pop()
        openTabs.value.push({ key, label: name, project: proj, path, content: data.content })
        tabContents.value = { ...tabContents.value, [key]: data.content }
        switchTab(key)
      }
    } catch { alert(t('openFileFailed')); return }
  } else {
    switchTab(key)
  }
  await nextTick()
  const source = editor ? editor.getValue() : (tabContents.value[key] || '')
  if (!/public\s+static\s+void\s+main\s*\(\s*String\s*\[\s*\]/.test(source)) {
    appendConsole('> ❌ ' + t('noMainMethod'), 'stderr')
    consoleTab.value = 'console'
    return
  }
  const mainClass = guessMainClass(path, source)
  await doSubmit(mainClass)
}

async function ctxDeleteJar() {
  const proj = ctxMenu.project; const jarName = ctxMenu.nodeName
  hideContextMenu()
  if (!confirm(t('confirmDeleteJar', { name: jarName }))) return
  try {
    const { data } = await http.delete(`/workspace/projects/${proj}/libs/${jarName}`)
    if (data.success) { await loadLibs(proj); await refreshTree(proj) }
    else alert(data.message)
  } catch { alert(t('deleteFailed')) }
}

// ==================== Decompile ====================

/** Context menu: Browse classes in a jar */
async function ctxBrowseJarClasses() {
  const project = ctxMenu.project
  const jarName = ctxMenu.nodeName
  hideContextMenu()

  // Get jar info
  decompileDialog.project = project
  decompileDialog.jarName = jarName
  decompileDialog.jarPath = ''
  decompileDialog.classes = []
  decompileDialog.filter = ''
  decompileDialog.decompiling = null
  decompileDialog.loading = true
  decompileDialog.visible = true

  try {
    const { data } = await http.post('/workspace/decompile/jar-info', { projectName: project, jarName })
    if (!data.success) {
      alert(data.message)
      decompileDialog.visible = false
      return
    }
    decompileDialog.jarPath = data.jarPath

    // Load class list
    const { data: classData } = await http.post('/workspace/decompile/list-classes', { jarPath: data.jarPath })
    if (classData.success) {
      decompileDialog.classes = classData.classes || []
    } else {
      alert(classData.message)
    }
  } catch (e) {
    alert(t('decompileFailed') + ': ' + e.message)
    decompileDialog.visible = false
  } finally {
    decompileDialog.loading = false
  }
}

/** Decompile a class and open it in a new editor tab */
async function doDecompileClass(className) {
  if (!decompileDialog.jarPath || decompileDialog.decompiling) return
  decompileDialog.decompiling = className

  try {
    const { data } = await http.post('/workspace/decompile/class', {
      jarPath: decompileDialog.jarPath,
      className
    })

    if (data.success && data.source) {
      // Create a unique key for decompiled files
      const jarBase = decompileDialog.jarName.replace(/\.jar$/i, '')
      const shortName = className.split('.').pop()
      const key = 'decompile:' + decompileDialog.jarName + ':' + className
      const label = shortName + '.java (decompiled)'

      // Check existing tab
      const existing = openTabs.value.find(t => t.key === key)
      if (existing) {
        switchTab(key)
        decompileDialog.decompiling = null
        return
      }

      // Open as a read-only tab
      openTabs.value.push({ key, label, project: decompileDialog.project, path: key, content: data.source, readonly: true })
      tabContents.value = { ...tabContents.value, [key]: data.source }
      switchTab(key)
    } else {
      alert(data.message || t('decompileFailed'))
    }
  } catch (e) {
    alert(t('decompileFailed') + ': ' + e.message)
  } finally {
    decompileDialog.decompiling = null
  }
}

// ==================== Reveal in Tree ====================
/** 在 Package Explorer 中定位到指定文件：展开项目和所有父目录 */
async function revealInTree(fileKey) {
  if (!fileKey) return
  const colonIdx = fileKey.indexOf(':')
  if (colonIdx < 0) return
  const project = fileKey.substring(0, colonIdx)
  
  // 确保项目已展开
  if (!expandedProjects.value[project]) {
    await toggleProjectExpand(project)
  }
  activeProject.value = project
  
  // 设置 revealPath，触发 TreeNodeItem 的自动展开
  revealPath.value = fileKey
}

// ==================== Search ====================
const searchVisible = ref(false)
const searchQuery = ref('')
const searchType = ref('all')
const searchExt = ref('')          // 当前选中的预设扩展名
const searchExtCustom = ref('')    // 自定义扩展名输入
const searchResults = ref([])
const searchSelectedIdx = ref(0)
const searchLoading = ref(false)
const searchInput = ref(null)
const searchResultsEl = ref(null)
let searchDebounceTimer = null

const searchPlaceholder = computed(() => {
  if (searchType.value === 'file') return t('searchFileHint')
  if (searchType.value === 'symbol') return t('searchSymbolHint')
  return t('searchAllHint')
})

/** 当前生效的扩展名过滤值 */
function getEffectiveExt() {
  if (searchExtCustom.value.trim()) return searchExtCustom.value.trim()
  return searchExt.value
}

function openSearch(type) {
  if (!activeProject.value && projects.value.length > 0) {
    activeProject.value = projects.value[0].name
  }
  if (!activeProject.value) return
  searchType.value = type || 'all'
  searchQuery.value = ''
  searchResults.value = []
  searchSelectedIdx.value = 0
  searchVisible.value = true
  nextTick(() => { if (searchInput.value) searchInput.value.focus() })
}

function setSearchType(type) {
  searchType.value = type
  if (searchQuery.value.trim()) doSearch()
  nextTick(() => { if (searchInput.value) searchInput.value.focus() })
}

function setSearchExt(ext) {
  searchExt.value = ext
  searchExtCustom.value = ''  // 清除自定义
  if (searchQuery.value.trim()) doSearch()
  nextTick(() => { if (searchInput.value) searchInput.value.focus() })
}

function onExtCustomInput() {
  searchExt.value = ''  // 使用自定义时清除预设选中
  if (searchQuery.value.trim()) {
    if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
    searchDebounceTimer = setTimeout(() => doSearch(), 300)
  }
}

function onSearchInput() {
  if (searchDebounceTimer) clearTimeout(searchDebounceTimer)
  searchDebounceTimer = setTimeout(() => doSearch(), 200)
}

async function doSearch() {
  const q = searchQuery.value.trim()
  if (!q || !activeProject.value) { searchResults.value = []; return }
  searchLoading.value = true
  try {
    const params = { q, type: searchType.value, max: 30 }
    const ext = getEffectiveExt()
    if (ext) params.ext = ext
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/search`, { params })
    if (data.success) {
      searchResults.value = data.results || []
      searchSelectedIdx.value = 0
    }
  } catch (e) {
    console.warn('Search failed', e)
  } finally {
    searchLoading.value = false
  }
}

function onSearchKeydown(e) {
  if (e.key === 'Escape') { searchVisible.value = false; return }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    if (searchSelectedIdx.value < searchResults.value.length - 1) {
      searchSelectedIdx.value++
      scrollSearchItemIntoView()
    }
    return
  }
  if (e.key === 'ArrowUp') {
    e.preventDefault()
    if (searchSelectedIdx.value > 0) {
      searchSelectedIdx.value--
      scrollSearchItemIntoView()
    }
    return
  }
  if (e.key === 'Enter') {
    e.preventDefault()
    if (searchResults.value.length > 0) {
      openSearchResult(searchResults.value[searchSelectedIdx.value])
    }
    return
  }
}

function scrollSearchItemIntoView() {
  nextTick(() => {
    if (!searchResultsEl.value) return
    const items = searchResultsEl.value.querySelectorAll('.search-result-item')
    if (items[searchSelectedIdx.value]) {
      items[searchSelectedIdx.value].scrollIntoView({ block: 'nearest' })
    }
  })
}

async function openSearchResult(item) {
  searchVisible.value = false
  const project = activeProject.value
  if (!project) return
  // 打开文件
  const path = item.path
  const name = path.includes('/') ? path.substring(path.lastIndexOf('/') + 1) : path
  await onSelectFile({ project, path, name })
  // 如果是符号且有行号，跳转到对应行
  if (item.type === 'symbol' && item.line > 0 && editor) {
    // 等待 DOM 更新和 editor model 加载完成
    await nextTick()
    setTimeout(() => {
      if (editor) {
        editor.revealLineInCenter(item.line)
        editor.setPosition({ lineNumber: item.line, column: 1 })
        editor.focus()
      }
    }, 50)
  }
}

// ==================== Console ====================
function appendConsole(text, type = 'stdout') {
  text.split('\n').forEach(line => { if (line !== '') consoleLines.value.push({ text: line, type }) })
  nextTick(() => { if (consoleBody.value) consoleBody.value.scrollTop = consoleBody.value.scrollHeight })
}
function clearConsole() { consoleLines.value = [] }

// ==================== Terminal ====================
const xtermTerminalRef = ref(null)
function clearTerminal() {
  if (xtermTerminalRef.value) xtermTerminalRef.value.clear()
}
// Deploy state (delegates to terminal component)
const deployHasYaml = computed(() => xtermTerminalRef.value?.hasDeployYaml || false)
const deployRunning = computed(() => xtermTerminalRef.value?.deployRunning || false)
const startDeploy = () => xtermTerminalRef.value?.startDeploy()
function onTerminalApplyCode(code) {
  if (editor) editor.setValue(code)
}
function onTerminalInsertCode(code) {
  if (editor) {
    const pos = editor.getPosition()
    editor.executeEdits('ai-insert', [{
      range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
      text: code
    }])
  }
}
function onTerminalRefreshProject(project) {
  if (project) refreshTree(project)
}

/** Refresh editor tabs after AI modifies files */
function onTerminalRefreshEditor() {
  if (activeProject.value) {
    reloadOpenTabs(activeProject.value)
  }
}

/**
 * Handle active AI task detected on reconnect.
 * Switches terminal to AI mode and displays a message.
 */
function onTerminalActiveAiTask({ sessionId, projectName }) {
  console.log('[App] Active AI task detected:', sessionId, projectName)
  // Switch to terminal tab
  consoleTab.value = 'terminal'
  // Set active project to the one running AI task
  activeProject.value = projectName
  // Tell terminal to enter AI mode with this session
  nextTick(() => {
    if (xtermTerminalRef.value) {
      xtermTerminalRef.value.sessionId = sessionId
      xtermTerminalRef.value.aiMode = true
      xtermTerminalRef.value.aiStreamActive = true
      xtermTerminalRef.value._aiFirstEvent = false
      xtermTerminalRef.value._aiPromptShown = false
      xtermTerminalRef.value.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m\r\n')
      xtermTerminalRef.value.write('\r\n\x1b[1;36m  Diatom AI Mode (Resumed)\x1b[0m\r\n')
      xtermTerminalRef.value.write(`  \x1b[1;33m${t('aiActiveTaskResume')}\x1b[0m\r\n`)
      xtermTerminalRef.value.write('\r\n\x1b[1;36m────────────────────────────────\x1b[0m\r\n')
    }
  })
}

/** Open a file in the editor from a click in the AI progress panel or changes list */
function onTerminalOpenFile({ project, path, name }) {
  if (!project || !path) return
  onSelectFile({ project, path, name })
}

// ==================== Git/VCS ====================
const gitInfo = reactive({ initialized: false, branch: '', clean: true, modified: [], added: [], untracked: [], removed: [], conflicting: [], branches: [], remoteUrl: '' })
const svnInfo = reactive({ initialized: false, clean: true, modified: [], added: [], untracked: [], removed: [], conflicting: [], output: '', exitCode: null, vcsType: 'svn', message: '' })
const gitCommits = ref([])
const gitLogLoading = ref(false)

function onGitTabClick() {
  consoleTab.value = 'git'
  if (activeProject.value) loadVcsInfo()
  nextTick(() => setupGitLogScroll())
}

function setupGitLogScroll() {
  const el = document.getElementById('git-log-tbody')
  if (!el) return
  el.onscroll = () => {
    if (gitLogLoading.value) return
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 50 && gitCommits.value.length >= 50) {
      loadGitLog(gitCommits.value.length)
    }
  }
}

async function loadGitInfo() {
  if (!activeProject.value) return
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/vcs/git/status`)
    Object.assign(gitInfo, {
      initialized: !!data.initialized,
      branch: data.branch || '',
      clean: !!data.clean,
      modified: data.modified || [],
      added: data.added || [],
      untracked: data.untracked || [],
      removed: data.removed || [],
      conflicting: data.conflicting || [],
      branches: data.branches || [],
      remoteUrl: data.remoteUrl || ''
    })
    if (data.initialized) loadGitLog(0)
  } catch (e) { console.warn('Git status failed', e) }
}

async function loadSvnInfo() {
  if (!activeProject.value) return
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/vcs/svn/status`)
    Object.assign(svnInfo, {
      initialized: !!data.initialized,
      clean: !!data.clean,
      modified: data.modified || [],
      added: data.added || [],
      untracked: data.untracked || [],
      removed: data.removed || [],
      conflicting: data.conflicting || [],
      output: data.output || '',
      exitCode: data.exitCode,
      vcsType: data.vcsType || 'svn',
      message: data.message || ''
    })
  } catch (e) { console.warn('SVN status failed', e) }
}

function loadVcsInfo() {
  if (activeProjectVcsType.value === 'svn') return loadSvnInfo()
  return loadGitInfo()
}

async function loadGitLog(skip) {
  if (!activeProject.value) return
  gitLogLoading.value = true
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/vcs/git/log`, { params: { max: 50, skip } })
    if (data.success && data.commits) {
      if (skip > 0) gitCommits.value = [...gitCommits.value, ...data.commits]
      else gitCommits.value = data.commits
    }
  } catch (e) { console.warn('Git log failed', e) }
  gitLogLoading.value = false
}

async function doGitInit() {
  if (!activeProject.value) return
  const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/init`)
  appendConsole('> ' + (data.message || t('gitInit')), data.success ? 'info' : 'stderr')
  if (data.success) loadVcsInfo()
}

function showGitCloneDialog() {
  openDialog(t('cloneRepoTitle'), t('cloneUrl'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    appendConsole('> ' + t('cloning') + ' ' + dialog.value.trim() + '...', 'info')
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/clone`, { url: dialog.value.trim() })
    appendConsole('> ' + (data.message || t('cloneDone')), data.success ? 'info' : 'stderr')
    if (data.success) { await refreshTree(activeProject.value); loadVcsInfo() }
  })
}

function showGitCommitDialog() {
  openDialog(t('commit') + ' Git', t('commitMessage'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/commit`, { message: dialog.value.trim(), addAll: false })
    appendConsole('> ' + (data.message || t('commit')), data.success ? 'info' : 'stderr')
    if (data.commitId) appendConsole('  commit: ' + data.commitId, 'info')
    loadVcsInfo()
  })
}

function showSvnCommitDialog() {
  openDialog(t('commit') + ' SVN', t('commitMessage'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/svn/commit`, { message: dialog.value.trim() })
    appendConsole('> ' + (data.message || data.output || t('commit')), data.success ? 'info' : 'stderr')
    loadSvnInfo()
  })
}

function showVcsCommitDialog() {
  if (activeProjectVcsType.value === 'svn') return showSvnCommitDialog()
  return showGitCommitDialog()
}

async function doGitPush() {
  if (!activeProject.value) return
  appendConsole('> ' + t('pushing'), 'info')
  const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/push`, {})
  appendConsole('> ' + (data.message || t('push')), data.success ? 'info' : 'stderr')
  if (data.detail) appendConsole(data.detail, 'info')
}

async function doGitPull() {
  if (!activeProject.value) return
  appendConsole('> ' + t('pulling'), 'info')
  const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/pull`, {})
  appendConsole('> ' + (data.message || t('pull')), data.success ? 'info' : 'stderr')
  if (data.mergeStatus) appendConsole('  merge: ' + data.mergeStatus, 'info')
  loadVcsInfo()
  await refreshTree(activeProject.value)
  if (data.success) await reloadOpenTabs(activeProject.value)
}

async function doSvnUpdate() {
  if (!activeProject.value) return
  appendConsole('> SVN update', 'info')
  const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/svn/update`)
  appendConsole('> ' + (data.message || data.output || t('pull')), data.success ? 'info' : 'stderr')
  loadSvnInfo()
  await refreshTree(activeProject.value)
  if (data.success) await reloadOpenTabs(activeProject.value)
}

function doVcsPull() {
  if (activeProjectVcsType.value === 'svn') return doSvnUpdate()
  return doGitPull()
}

function showGitBranchDialog() {
  if (!activeProject.value) return
  branchDialog.filter = ''
  branchDialog.branches = gitInfo.branches || []
  branchDialog.currentBranch = gitInfo.branch || ''
  branchDialog.loading = false
  branchDialog.visible = true
}

async function doSwitchBranch(branchName, createIfNotExist) {
  if (!branchName || !activeProject.value) return
  if (branchName === branchDialog.currentBranch) {
    branchDialog.visible = false
    return
  }
  branchDialog.loading = true
  try {
    let { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/checkout`, { branch: branchName, create: false })
    if (!data.success && createIfNotExist) {
      const { data: newData } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/checkout`, { branch: branchName, create: true })
      data = newData
    }
    appendConsole('> ' + (data.message || t('checkout')), data.success ? 'info' : 'stderr')
    if (data.success) {
      branchDialog.visible = false
      loadVcsInfo()
      await refreshTree(activeProject.value)
      // 刷新所有属于该项目的已打开 tab 的内容
      await reloadOpenTabs(activeProject.value)
    }
  } catch (e) {
    appendConsole('> ' + t('checkoutFailed') + ': ' + (e.message || e), 'stderr')
  } finally {
    branchDialog.loading = false
  }
}

function showGitMergeDialog() {
  openDialog(t('mergeTitle'), t('mergeBranch'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/merge`, { branch: dialog.value.trim() })
    appendConsole('> ' + (data.message || t('merge')), data.success ? 'info' : 'stderr')
    if (data.conflicts) appendConsole('  ' + t('conflictFiles') + ': ' + JSON.stringify(data.conflicts), 'stderr')
    loadVcsInfo()
    await refreshTree(activeProject.value)
    if (data.success) await reloadOpenTabs(activeProject.value)
  })
}

async function doGitCherryPick(commitId) {
  if (!activeProject.value) return
  appendConsole('> ' + t('cherryPicking') + ' ' + commitId.substring(0, 7) + '...', 'info')
  const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/cherry-pick`, { commitId })
  appendConsole('> ' + (data.message || t('cherryPick')), data.success ? 'info' : 'stderr')
  loadVcsInfo()
  if (data.success) await reloadOpenTabs(activeProject.value)
}

/** 重新加载指定项目所有已打开 tab 的文件内容（分支切换/merge/cherry-pick 后调用） */
async function reloadOpenTabs(proj) {
  for (const tab of [...openTabs.value]) {
    if (tab.project !== proj) continue
    try {
      const { data } = await http.get(`/workspace/projects/${tab.project}/file`, { params: { path: tab.path } })
      if (data.success) {
        tabContents.value = { ...tabContents.value, [tab.key]: data.content }
        if (tab.key === currentTab.value && editor) {
          editor.setValue(data.content)
        }
      }
    } catch {
      // 文件在新分支中不存在，关闭该 tab
      const idx = openTabs.value.findIndex(t => t.key === tab.key)
      if (idx !== -1) {
        openTabs.value.splice(idx, 1)
        const newContents = { ...tabContents.value }
        delete newContents[tab.key]
        tabContents.value = newContents
      }
    }
  }
  // 如果当前 tab 被关闭了，切换到第一个可用 tab
  if (currentTab.value && !openTabs.value.find(t => t.key === currentTab.value)) {
    if (openTabs.value.length > 0) switchTab(openTabs.value[0].key)
    else { currentTab.value = ''; activeFile.value = ''; if (editor) editor.setValue(DEFAULT_CODE) }
  }
}

async function showCommitDiff(commitId) {
  if (!activeProject.value) return
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/vcs/git/diff`, { params: { oldRef: commitId + '~1', newRef: commitId } })
    if (data.success && data.diffs) {
      consoleTab.value = 'console'
      appendConsole(t('diffHeader', { commitId: commitId.substring(0, 7) }), 'info')
      for (const d of data.diffs) {
        const icon = d.changeType === 'ADD' ? '➕' : d.changeType === 'DELETE' ? '➖' : '✏️'
        appendConsole(`  ${icon} ${d.changeType}: ${d.newPath || d.oldPath}`, d.changeType === 'DELETE' ? 'stderr' : 'info')
      }
    }
  } catch (e) {
    // 首次 commit 没有 parent
    appendConsole('> ' + t('cannotGetDiff'), 'stderr')
  }
}

// ==================== VCS Sidebar Handlers ====================
async function handleVcsStage(paths) {
  if (!activeProject.value || !paths.length) return
  try {
    const endpoint = activeProjectVcsType.value === 'svn' ? 'svn/add' : 'git/add'
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/${endpoint}`, { paths })
    appendConsole('> ' + (data.message || data.output || t('vcsSidebar.actions.stage')), data.success ? 'info' : 'stderr')
    loadVcsInfo()
  } catch (e) {
    appendConsole('> ' + t('vcsSidebar.actions.stage') + ': ' + e.message, 'stderr')
  }
}

async function handleVcsUnstage(paths) {
  if (activeProjectVcsType.value === 'svn') return
  if (!activeProject.value || !paths.length) return
  try {
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/unstage`, { paths })
    appendConsole('> ' + (data.message || t('vcsSidebar.actions.unstage')), data.success ? 'info' : 'stderr')
    loadVcsInfo()
  } catch (e) {
    appendConsole('> ' + t('vcsSidebar.actions.unstage') + ': ' + e.message, 'stderr')
  }
}

async function handleVcsDiscard(paths) {
  if (activeProjectVcsType.value === 'svn') return
  if (!activeProject.value || !paths.length) return
  try {
    const { data } = await http.post(`/workspace/projects/${activeProject.value}/vcs/git/discard`, { paths })
    appendConsole('> ' + (data.message || t('vcsSidebar.actions.discard')), data.success ? 'info' : 'stderr')
    loadVcsInfo()
  } catch (e) {
    appendConsole('> ' + t('vcsSidebar.actions.discard') + ': ' + e.message, 'stderr')
  }
}

// ==================== Theme & Settings ====================
function toggleTheme() {
  isDark.value = !isDark.value
  ideSettings.theme = isDark.value ? 'dark' : 'light'
  applyTheme()
  localStorage.setItem('ide-theme', ideSettings.theme)
  // 同步到后端
  http.put('/ide/settings', { theme: ideSettings.theme }).catch(() => {})
}

function onThemeChange() {
  isDark.value = ideSettings.theme === 'dark'
  applyTheme()
  localStorage.setItem('ide-theme', ideSettings.theme)
}

function onLanguageChange() {
  const lang = ideSettings.language
  localStorage.setItem('ide-lang', lang)
  // 通知 i18n 语言变更
  window.dispatchEvent(new CustomEvent('lang-change', { detail: lang }))
}

function applyTheme() {
  if (editor) {
    monaco.editor.setTheme(isDark.value ? 'vs-dark' : 'vs')
  }
  document.body.style.background = isDark.value ? '#2b2b2b' : '#f0f0f0'
}

async function loadSettings() {
  // 先从 localStorage 恢复主题和语言
  const savedTheme = localStorage.getItem('ide-theme')
  if (savedTheme === 'dark' || savedTheme === 'light') {
    ideSettings.theme = savedTheme
    isDark.value = savedTheme === 'dark'
  }
  const savedLang = localStorage.getItem('ide-lang')
  if (savedLang === 'en' || savedLang === 'zh') {
    ideSettings.language = savedLang
  }
  // 从后端加载完整设置
  try {
    const { data } = await http.get('/ide/settings')
    // 主题优先用 localStorage（上次用户选择）
    if (!savedTheme && data.theme) {
      ideSettings.theme = data.theme
      isDark.value = data.theme === 'dark'
    }
    // 语言优先用 localStorage
    if (!savedLang && data.language) {
      ideSettings.language = data.language
      localStorage.setItem('ide-lang', data.language)
    }
    ideSettings.jdkVersion = data.jdkVersion || 25
    ideSettings.javaHome = data.javaHome || ''
    ideSettings.mavenHome = data.mavenHome || ''
    ideSettings.mavenUserSettings = data.mavenUserSettings || ''
    ideSettings.mavenLocalRepository = data.mavenLocalRepository || ''
    ideSettings.gradleUserHome = data.gradleUserHome || ''
    ideSettings.gitPath = data.gitPath || ''
    ideSettings.svnPath = data.svnPath || ''
    ideSettings.aiApiUrl = data.aiApiUrl || ''
    ideSettings.aiApiToken = data.aiApiToken || ''
    ideSettings.aiModel = data.aiModel || ''
    ideSettings.aiEnabled = !!data.aiEnabled
    ideSettings.mode = data.mode || 'standard'
    ideSettings.aiConfigRequired = data.aiConfigRequired !== false
  } catch (e) {
    console.warn('Failed to load IDE settings', e)
  }
  applyTheme()
}

async function saveSettings() {
  try {
    const { data } = await http.put('/ide/settings', {
      theme: ideSettings.theme,
      language: ideSettings.language,
      jdkVersion: ideSettings.jdkVersion,
      javaHome: ideSettings.javaHome,
      mavenHome: ideSettings.mavenHome,
      mavenUserSettings: ideSettings.mavenUserSettings,
      mavenLocalRepository: ideSettings.mavenLocalRepository,
      gradleUserHome: ideSettings.gradleUserHome,
      gitPath: ideSettings.gitPath,
      svnPath: ideSettings.svnPath,
      // Only send local AI config when the starter says it is required
      ...(ideSettings.aiConfigRequired ? {
        aiApiUrl: ideSettings.aiApiUrl,
        aiApiToken: ideSettings.aiApiToken,
        aiModel: ideSettings.aiModel
      } : {}),
      aiEnabled: ideSettings.aiEnabled
    })
    // 更新本地
    Object.assign(ideSettings, result)
    isDark.value = ideSettings.theme === 'dark'
    localStorage.setItem('ide-theme', ideSettings.theme)
    localStorage.setItem('ide-lang', ideSettings.language)
    applyTheme()
    showSettingsPanel.value = false
    appendConsole('> ' + t('settingsSaved'), 'info')
  } catch (e) {
    appendConsole('> ' + t('saveSettingsFailed') + ': ' + e.message, 'stderr')
  }
}

/** 编译前检查环境配置 */
function checkEnvBeforeCompile() {
  const warnings = []
  if (!ideSettings.javaHome) warnings.push(t('javaHomeNotConfigured'))
  if (warnings.length > 0) {
    warnings.forEach(w => appendConsole('⚠ ' + w, 'stderr'))
    return false
  }
  return true
}

// ==================== LSP ====================
let lspReconnectTimer = null
const LSP_RECONNECT_DELAY = 3000

function connectLsp() {
  if (lspReconnectTimer) { clearTimeout(lspReconnectTimer); lspReconnectTimer = null }
  // 重连时清空已打开 URI 集合，因为服务端状态已重置
  lspOpenedUris.clear()
  const ws = new WebSocket('ws://localhost:8080/java-lsp')
  lspWebSocket = ws
  ws.onerror = () => { lspStatus.value = t('lspConnectFailed') }
  ws.onclose = () => {
    lspConnection = null
    lspStatus.value = t('lspDisconnected')
    // 自动重连
    if (!lspReconnectTimer) {
      lspReconnectTimer = setTimeout(() => {
        lspReconnectTimer = null
        lspStatus.value = t('lspReconnecting')
        connectLsp()
      }, LSP_RECONNECT_DELAY)
    }
  }
  listen({
    webSocket: ws,
    onConnection: (connection) => {
      lspConnection = connection; lspStatus.value = t('lspConnected'); connection.listen()
      connection.sendRequest('initialize', {
        processId: null, rootUri: 'file:///workspace',
        capabilities: {
          textDocument: {
            synchronization: { dynamicRegistration: false },
            completion: { completionItem: { snippetSupport: true } },
            hover: { dynamicRegistration: false },
            definition: { dynamicRegistration: false },
            references: { dynamicRegistration: false },
            implementation: { dynamicRegistration: false },
            codeAction: { dynamicRegistration: false, codeActionLiteralSupport: { codeActionKind: { valueSet: ['quickfix', 'source'] } } }
          },
          workspace: { didChangeConfiguration: { dynamicRegistration: false } }
        }, initializationOptions: {}
      }).then(() => {
        connection.sendNotification('initialized', {})
        // 对当前打开的 Java 文件发送 didOpen
        const uri = getCurrentDocUri()
        const text = editor ? editor.getValue() : DEFAULT_CODE
        lspOpenedUris.add(uri)
        docVersion++
        connection.sendNotification('textDocument/didOpen', {
          textDocument: { uri, languageId: 'java', version: docVersion, text }
        })
      }).catch(() => { lspStatus.value = t('lspInitFailed') })

      connection.onNotification('textDocument/publishDiagnostics', (params) => {
        if (!editor) return; const model = editor.getModel(); if (!model) return
        // 只处理当前打开文件的诊断
        const currentUri = getCurrentDocUri()
        if (params.uri && params.uri !== currentUri && params.uri !== DOC_URI) return
        if (!isCurrentTabJava()) return
        const fileName = currentUri.split('/').pop() || 'Main.java'
        problems.value = (params.diagnostics || []).map(d => ({ severity: d.severity === 1 ? 'error' : 'warning', message: d.message, resource: fileName, line: d.range.start.line + 1, col: d.range.start.character + 1 }))
        monaco.editor.setModelMarkers(model, 'jdt-core', (params.diagnostics || []).map(d => ({
          severity: d.severity === 1 ? monaco.MarkerSeverity.Error : d.severity === 2 ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Info,
          startLineNumber: d.range.start.line + 1, startColumn: d.range.start.character + 1,
          endLineNumber: d.range.end.line + 1, endColumn: d.range.end.character + 1,
          message: d.message, source: d.source || 'jdt-core'
        })))
      })
      connection.onClose(() => { lspConnection = null; lspStatus.value = 'LSP: 已断开'; /* reconnect handled by ws.onclose */ })
    }
  })
}

function sendDidChange() {
  if (!lspConnection || !editor || !isCurrentTabJava()) return
  const uri = getCurrentDocUri()
  docVersion++
  if (!lspOpenedUris.has(uri)) {
    // 该文件还没有 didOpen 过，先发送 didOpen
    lspOpenedUris.add(uri)
    lspConnection.sendNotification('textDocument/didOpen', {
      textDocument: { uri, languageId: 'java', version: docVersion, text: editor.getValue() }
    })
  } else {
    lspConnection.sendNotification('textDocument/didChange', {
      textDocument: { uri, version: docVersion }, contentChanges: [{ text: editor.getValue() }]
    })
  }
}

// ==================== 非 Java 文件轻量校验 ====================

function goToProblem(p) {
  if (!editor || !p.line) return
  editor.revealLineInCenter(p.line)
  editor.setPosition({ lineNumber: p.line, column: p.col || 1 })
  editor.focus()
}

function validateCurrentFile() {
  if (!editor) return
  const tab = openTabs.value.find(t => t.key === currentTab.value)
  if (!tab) return
  const lang = getLanguageByFileName(tab.label)
  if (lang === 'java') return // Java 由 LSP 处理
  const text = editor.getValue()
  const model = editor.getModel()
  if (!model) return
  let markers = []
  if (lang === 'xml') markers = validateXml(text)
  else if (lang === 'yaml') markers = validateYaml(text)
  else if (lang === 'ini') markers = validateProperties(text)
  else if (lang === 'json') markers = validateJson(text)
  monaco.editor.setModelMarkers(model, 'custom-lint', markers)
  problems.value = markers.map(m => ({
    severity: m.severity === monaco.MarkerSeverity.Error ? 'error' : 'warning',
    message: m.message, resource: tab.label, line: m.startLineNumber, col: m.startColumn || 1
  }))
}

function validateXml(text) {
  const markers = []
  const lines = text.split('\n')
  const tagStack = []
  // 跳过 XML 声明和注释的简单状态
  let inComment = false
  for (let i = 0; i < lines.length; i++) {
    let line = lines[i]
    // 处理多行注释
    if (inComment) {
      const endIdx = line.indexOf('-->')
      if (endIdx >= 0) { inComment = false; line = line.substring(endIdx + 3) }
      else continue
    }
    // 去除行内注释
    let processed = ''
    let ci = 0
    while (ci < line.length) {
      const cmtStart = line.indexOf('<!--', ci)
      if (cmtStart < 0) { processed += line.substring(ci); break }
      processed += line.substring(ci, cmtStart)
      const cmtEnd = line.indexOf('-->', cmtStart + 4)
      if (cmtEnd >= 0) { ci = cmtEnd + 3 }
      else { inComment = true; break }
    }
    line = processed
    // 跳过 XML 声明 <?...?>
    line = line.replace(/<\?[^?]*\?>/g, '')
    // 跳过 CDATA
    line = line.replace(/<!\[CDATA\[.*?\]\]>/g, '')
    // 匹配标签
    const tagRe = /<\/?([a-zA-Z_][\w:.-]*)[^>]*\/?>/g
    let m
    while ((m = tagRe.exec(line)) !== null) {
      const full = m[0]
      const tagName = m[1]
      if (full.startsWith('</')) {
        // 闭合标签
        if (tagStack.length === 0 || tagStack[tagStack.length - 1].name !== tagName) {
          const expected = tagStack.length > 0 ? tagStack[tagStack.length - 1].name : t('none')
          markers.push({ severity: monaco.MarkerSeverity.Error, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: line.length + 1, message: t('tagMismatch', { expected, tagName }) })
        } else { tagStack.pop() }
      } else if (!full.endsWith('/>')) {
        // 开始标签（非自闭合）
        tagStack.push({ name: tagName, line: i + 1 })
      }
    }
    // 检查尖括号基本配对
    const opens = (line.match(/</g) || []).length
    const closes = (line.match(/>/g) || []).length
    if (opens !== closes) {
      markers.push({ severity: monaco.MarkerSeverity.Warning, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: line.length + 1, message: t('angleBracketsMismatch') })
    }
  }
  // 检查未闭合的标签
  for (const t of tagStack) {
    markers.push({ severity: monaco.MarkerSeverity.Error, startLineNumber: t.line, startColumn: 1, endLineNumber: t.line, endColumn: 1, message: t('unclosedTag', { name: t.name }) })
  }
  return markers
}

function validateYaml(text) {
  const markers = []
  const lines = text.split('\n')
  let prevIndent = 0
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (line.trim() === '' || line.trim().startsWith('#')) continue
    // 检查 Tab 缩进
    if (line.match(/^\t/)) {
      markers.push({ severity: monaco.MarkerSeverity.Error, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: line.length + 1, message: t('yamlNoTab') })
    }
    const indent = line.match(/^( *)/)[1].length
    // 检查奇数缩进（常见错误）
    if (indent % 2 !== 0) {
      markers.push({ severity: monaco.MarkerSeverity.Warning, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: indent + 1, message: t('oddIndentWarning') })
    }
    const trimmed = line.trim()
    // 检查键值对基本格式（非列表项）
    if (!trimmed.startsWith('-') && !trimmed.startsWith('#') && trimmed.includes(':')) {
      const colonIdx = trimmed.indexOf(':')
      // 冒号后面应该有空格或者是行尾
      if (colonIdx < trimmed.length - 1 && trimmed[colonIdx + 1] !== ' ') {
        markers.push({ severity: monaco.MarkerSeverity.Warning, startLineNumber: i + 1, startColumn: line.indexOf(':') + 2, endLineNumber: i + 1, endColumn: line.indexOf(':') + 3, message: t('colonSpaceWarning') })
      }
    }
    // 检查重复的键（同一缩进层级）— 简单检测
    prevIndent = indent
  }
  return markers
}

function validateProperties(text) {
  const markers = []
  const lines = text.split('\n')
  const keys = new Map()
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i].trim()
    if (line === '' || line.startsWith('#') || line.startsWith('!')) continue
    // 检查是否有 = 或 :
    const sepIdx = line.search(/[=:]/)
    if (sepIdx < 0) {
      markers.push({ severity: monaco.MarkerSeverity.Warning, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: lines[i].length + 1, message: t('missingSeparatorWarning') })
      continue
    }
    const key = line.substring(0, sepIdx).trim()
    if (key === '') {
      markers.push({ severity: monaco.MarkerSeverity.Error, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: sepIdx + 1, message: t('emptyKeyWarning') })
    } else if (keys.has(key)) {
      markers.push({ severity: monaco.MarkerSeverity.Warning, startLineNumber: i + 1, startColumn: 1, endLineNumber: i + 1, endColumn: sepIdx + 1, message: t('duplicateKey', { key, line: keys.get(key) }) })
    } else {
      keys.set(key, i + 1)
    }
  }
  return markers
}

function validateJson(text) {
  const markers = []
  try { JSON.parse(text) }
  catch (e) {
    const m = e.message.match(/position (\d+)/)
    let line = 1, col = 1
    if (m) {
      const pos = parseInt(m[1])
      const before = text.substring(0, pos)
      line = (before.match(/\n/g) || []).length + 1
      col = pos - before.lastIndexOf('\n')
    }
    markers.push({ severity: monaco.MarkerSeverity.Error, startLineNumber: line, startColumn: col, endLineNumber: line, endColumn: col + 1, message: t('jsonSyntaxError') + ': ' + e.message })
  }
  return markers
}

function mapCompletionKind(k) {
  const m = { 1: monaco.languages.CompletionItemKind.Text, 2: monaco.languages.CompletionItemKind.Method, 3: monaco.languages.CompletionItemKind.Function, 5: monaco.languages.CompletionItemKind.Field, 6: monaco.languages.CompletionItemKind.Variable, 7: monaco.languages.CompletionItemKind.Class, 8: monaco.languages.CompletionItemKind.Interface, 13: monaco.languages.CompletionItemKind.Enum, 14: monaco.languages.CompletionItemKind.Keyword }
  return m[k] || monaco.languages.CompletionItemKind.Text
}

// ==================== Resize ====================
function startResizeSidebar(e) {
  const startX = e.clientX, startW = sidebarWidth.value
  const onMove = (ev) => { sidebarWidth.value = Math.max(120, Math.min(500, startW + ev.clientX - startX)) }
  const onUp = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
  document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp)
}
function startResizeConsole(e) {
  const startY = e.clientY, startH = consoleHeight.value
  const onMove = (ev) => { consoleHeight.value = Math.max(80, Math.min(600, startH - (ev.clientY - startY))) }
  const onUp = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
  document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp)
}
function startResizeRightPanel(e) {
  const startX = e.clientX, startW = rightPanelWidth.value
  const onMove = (ev) => { rightPanelWidth.value = Math.max(180, Math.min(500, startW - (ev.clientX - startX))) }
  const onUp = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
  document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp)
}

// ==================== Maven/Gradle Tool Window ====================
async function loadMavenPanel() {
  if (!activeProject.value) return
  // Load dependencies
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/libs`)
    if (data.success) {
      mavenDeps.value = data.dependencies || []
    }
  } catch {}
  // Load plugins from pom.xml
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/file`, { params: { path: 'pom.xml' } })
    if (data.success && data.content) {
      mavenPlugins.value = parsePomPlugins(data.content)
    }
  } catch {}
  // Detect main classes
  try {
    const { data } = await http.get('/detect-main-classes', { params: { projectName: activeProject.value } })
    if (data.success) mavenMainClasses.value = data.mainClasses || []
  } catch {}
}

function parsePomPlugins(pomContent) {
  const plugins = []
  const pluginRe = /<plugin>\s*(?:<groupId>([^<]*)<\/groupId>\s*)?<artifactId>([^<]*)<\/artifactId>/gs
  let m
  while ((m = pluginRe.exec(pomContent)) !== null) {
    const artifactId = m[2].trim()
    // 常见 Maven 插件的 goals
    const goalMap = {
      'maven-compiler-plugin': ['compile', 'testCompile'],
      'maven-surefire-plugin': ['test'],
      'maven-jar-plugin': ['jar'],
      'maven-install-plugin': ['install'],
      'maven-deploy-plugin': ['deploy'],
      'maven-clean-plugin': ['clean'],
      'maven-resources-plugin': ['resources', 'testResources'],
      'maven-war-plugin': ['war'],
      'maven-shade-plugin': ['shade'],
      'maven-assembly-plugin': ['assembly', 'single'],
      'spring-boot-maven-plugin': ['run', 'repackage'],
      'maven-source-plugin': ['jar', 'jar-no-fork'],
      'maven-javadoc-plugin': ['javadoc', 'jar'],
    }
    plugins.push({
      groupId: m[1] ? m[1].trim() : 'org.apache.maven.plugins',
      artifactId,
      goals: goalMap[artifactId] || ['help'],
      _expanded: false
    })
  }
  return plugins
}

async function loadGradlePanel() {
  if (!activeProject.value) return
  // Load dependencies
  try {
    const { data } = await http.get(`/workspace/projects/${activeProject.value}/libs`)
    if (data.success) {
      gradleDeps.value = data.dependencies || []
    }
  } catch {}
  // Detect main classes
  try {
    const { data } = await http.get('/detect-main-classes', { params: { projectName: activeProject.value } })
    if (data.success) gradleMainClasses.value = data.mainClasses || []
  } catch {}
}

function showMavenGoalDialog() {
  openDialog(t('mavenGoalTitle'), t('mavenGoalHint'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    doMavenBuild(dialog.value.trim())
  })
}

function showGradleTaskDialog() {
  openDialog(t('gradleTaskTitle'), t('gradleTaskHint'), '', async () => {
    if (!dialog.value.trim()) return
    dialog.visible = false
    doGradleBuild(dialog.value.trim())
  })
}

// ==================== Auth Functions ====================
async function checkAuth() {
  // 检查 localStorage 中的 token
  const savedToken = localStorage.getItem('ide-auth-token')
  if (savedToken) {
    try {
      const { data } = await http.get('/auth/validate', { headers: { 'X-Auth-Token': savedToken } })
      // ApiResponse wrapper: data.data contains { valid, username }
        if (data.valid) {
        authToken.value = savedToken
        authUser.value = data.username
        showLogin.value = false
        startKickPoll()
        connectLsp()
        loadProjects()
        loadSettings()
        checkRunStatus()
        return
      }
    } catch {}
  }
  // 检查是否可以免密登录
  try {
    const { data } = await http.get('/auth/check')
    if (data.autoLogin) {
      authToken.value = data.token
      authUser.value = data.username
      localStorage.setItem('ide-auth-token', data.token)
      showLogin.value = false
      startKickPoll()
      connectLsp()
      loadProjects()
      loadSettings()
      checkRunStatus()
      return
    }
    loginForm.username = data.currentUser || ''
  } catch {}
  showLogin.value = true
}

async function doLogin() {
  loginForm.error = ''
  loginForm.loading = true
  try {
    const { data } = await http.post('/auth/login', {
      username: loginForm.username, password: loginForm.password
    })
    // ApiResponse wrapper: data.data contains { success, status, token, username, ... }
    if (!data.success) {
      loginForm.error = data.message || t('loginFailed')
      return
    }
    if (data.status === 'success') {
      console.log('[doLogin] success, calling loadProjects...')
      authToken.value = data.token
      authUser.value = data.username
      localStorage.setItem('ide-auth-token', data.token)
      showLogin.value = false
      startKickPoll()
      connectLsp()
      loadProjects()
      loadSettings()
      checkRunStatus()
    } else if (data.status === 'needConfirm') {
      loginForm.error = t('userInUse', { user: data.currentUser })
      // 长轮询等待结果
      try {
        const { data: waitData } = await http.post('/auth/login/wait', { requestId: data.requestId })
        if (waitData.status === 'success') {
          authToken.value = waitData.token
          authUser.value = waitData.username
          localStorage.setItem('ide-auth-token', waitData.token)
          showLogin.value = false
          startKickPoll()
          connectLsp()
          loadProjects()
          loadSettings()
          checkRunStatus()
        } else {
          loginForm.error = waitResult.message || t('loginRejected')
        }
      } catch {
        loginForm.error = t('waitFailed')
      }
    }
  } catch (e) {
    loginForm.error = t('requestFailed') + ': ' + (e.message || e)
  } finally {
    loginForm.loading = false
  }
}

async function doLogout() {
  try {
    await http.post('/auth/logout')
  } catch (e) {
    console.warn('[doLogout] logout request failed:', e)
  }
  authToken.value = ''
  authUser.value = ''
  localStorage.removeItem('ide-auth-token')
  showLogin.value = true
  stopKickPoll()
}

function startKickPoll() {
  stopKickPoll()
  kickPollTimer = setInterval(async () => {
    if (!authToken.value) return
    try {
      const { data } = await http.get('/auth/kick/pending')
        if (data.hasPending) {
        kickAlert.visible = true
        kickAlert.requestId = data.requestId
        kickAlert.newUser = data.newUser
      } else if (kickAlert.visible) {
        // No pending kick, close dialog if open
        kickAlert.visible = false
      }
    } catch {}
  }, 3000)
}

function stopKickPoll() {
  if (kickPollTimer) { clearInterval(kickPollTimer); kickPollTimer = null }
}

async function respondKick(approve) {
  try {
    await http.post('/auth/kick/respond', {
      requestId: kickAlert.requestId, approve
    })
  } catch {}
  kickAlert.visible = false
  if (approve) {
    // 自己被踢了
    doLogout()
  }
}

// ==================== Editor State Sync (for AI MCP) ====================
let editorStateSyncTimer = null

function startEditorStateSync() {
  if (editorStateSyncTimer) clearInterval(editorStateSyncTimer)
  editorStateSyncTimer = setInterval(() => {
    if (!editor) return
    const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
    if (!tab) return
    const pos = editor.getPosition()
    const selection = editor.getSelection()
    const model = editor.getModel()
    let selectedText = ''
    if (selection && !selection.isEmpty()) {
      selectedText = model ? model.getValueInRange(selection) : ''
    }
    const tabs = openTabs.value.map(t => t.key)
    const body = {
      filePath: tab.path,
      projectName: tab.project,
      language: getLanguageByFileName(tab.label),
      cursorLine: pos ? pos.lineNumber : 1,
      cursorColumn: pos ? pos.column : 1,
      fileContent: editor.getValue(),
      selectedText: selectedText,
      selectionStartLine: selection ? selection.startLineNumber : 0,
      selectionEndLine: selection ? selection.endLineNumber : 0,
      openTabs: tabs
    }
    http.post('/ide/editor/state', body).catch(() => {})
  }, 1500)
}

// ==================== Editor Setup ====================
onMounted(async () => {
  if (!editorContainer.value) return
  const model = monaco.editor.createModel(DEFAULT_CODE, 'java', monaco.Uri.parse(DOC_URI))
  editor = monaco.editor.create(editorContainer.value, {
    model, theme: 'vs-dark', fontSize: 14, minimap: { enabled: true, maxColumn: 80 },
    automaticLayout: true, scrollBeyondLastLine: false, tabSize: 4,
    renderLineHighlight: 'all', bracketPairColorization: { enabled: true },
    lightbulb: { enabled: true }, glyphMargin: true,
  })
  editor.onDidChangeCursorPosition((e) => { cursorLine.value = e.position.lineNumber; cursorCol.value = e.position.column })
  editor.onDidChangeModelContent(() => { sendDidChange(); validateCurrentFile(); scheduleAutoSave() })

  // Sync tab system when Monaco internally navigates to a different model (e.g. peek widget, F12)
  editor.onDidChangeModel(() => {
    const model = editor && editor.getModel()
    if (!model) return
    const uri = model.uri.toString()
    if (!uri.startsWith('file:///workspace/')) return
    const relativePath = uri.replace('file:///workspace/', '')
    const projectEnd = relativePath.indexOf('/')
    if (projectEnd <= 0) return
    const project = relativePath.substring(0, projectEnd)
    const filePath = relativePath.substring(projectEnd + 1)
    const key = project + ':' + filePath
    if (key !== currentTab.value) {
      handleWorkspaceNavigation(uri, null)
    }
  })

  // Override editor openCodeEditor to handle file:///workspace/ URI navigation from peek widget
  setupPeekNavigation()

  // Start periodic editor state sync for AI MCP context
  startEditorStateSync()

  // Setup debug gutter click handler
  setupDebugEditor()

  monaco.languages.registerCompletionItemProvider('java', {
    triggerCharacters: ['.'],
    provideCompletionItems: async (model, position) => {
      if (!lspConnection) return { suggestions: [] }
      try {
        const result = await lspConnection.sendRequest('textDocument/completion', {
          textDocument: { uri: getCurrentDocUri() }, position: { line: position.lineNumber - 1, character: position.column - 1 }
        })
        const items = Array.isArray(result) ? result : (result?.items || [])
        return { suggestions: items.map(item => {
          const s = { label: item.detail ? { label: item.label || '', description: item.detail } : (item.label || ''), kind: mapCompletionKind(item.kind), insertText: item.insertText || item.label || '', detail: item.detail || '', sortText: item.sortText || item.label || '' }
          if (item.additionalTextEdits?.length > 0) {
            s.additionalTextEdits = item.additionalTextEdits.map(edit => ({ range: new monaco.Range(edit.range.start.line + 1, edit.range.start.character + 1, edit.range.end.line + 1, edit.range.end.character + 1), text: edit.newText }))
          }
          return s
        })}
      } catch { return { suggestions: [] } }
    }
  })

  // ==================== Code Navigation Providers ====================

  // Note: registerTextModelContentProvider was removed in Monaco 0.36+
  // References will work via Monaco's built-in model creation for remote URIs
  // If needed, use a custom implementation with textmate grammars or worker-based approach

  // Go to Definition (F12 / Ctrl+Click)
  monaco.languages.registerDefinitionProvider('java', {
    provideDefinition: async (model, position, token) => {
      const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
      if (!tab || !tab.project) return null
      try {
        const { data } = await http.post(`/workspace/projects/${tab.project}/navigate/definition`, {
          filePath: tab.path, line: position.lineNumber, column: position.column
        })
        if (token.isCancellationRequested) return null
            if (data.success && data.locations && data.locations.length > 0) {
          // 确保所有目标文件都有对应的 Monaco model，避免 Peek 时"Model not found"
          await Promise.all(data.locations.map(loc =>
            ensureModelExists('file:///workspace/' + tab.project + '/' + (loc.filePath || ''), tab.project, loc.filePath || '')
          ))
          if (token.isCancellationRequested) return null
          const locations = data.locations.map(loc => {
            const startCol = loc.column || 1
            const nameLen = (loc.name || '').length || 1
            const uriStr = 'file:///workspace/' + tab.project + '/' + (loc.filePath || '')
            console.log('[goToDefinition] result:', { uri: uriStr, line: loc.line, col: startCol, nameLen })
            return {
              uri: monaco.Uri.parse(uriStr),
              range: new monaco.Range(loc.line, startCol, loc.line, startCol + nameLen)
            }
          })
          console.log('[goToDefinition] returning', locations.length, 'locations')
          return locations
        }
        console.log('[goToDefinition] no results found, data:', data)
      } catch (e) {
        console.error('[goToDefinition] error:', e)
      }
      return null
    }
  })

  // Go to Implementation (Ctrl+F12) — uses Monaco built-in peek UI
  monaco.languages.registerImplementationProvider('java', {
    provideImplementation: async (model, position, token) => {
      const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
      if (!tab || !tab.project) return null
      try {
        // Fire-and-forget reindex so this query is fast (next call will benefit)
        http.post(`/workspace/projects/${tab.project}/reindex`).catch(() => {})
        const { data } = await http.post(`/workspace/projects/${tab.project}/navigate/implementations`, {
          filePath: tab.path, line: position.lineNumber, column: position.column
        })
        if (token.isCancellationRequested) return null
            if (data.success && data.locations && data.locations.length > 0) {
          await Promise.all(data.locations.map(loc =>
            ensureModelExists('file:///workspace/' + tab.project + '/' + (loc.filePath || ''), tab.project, loc.filePath || '')
          ))
          if (token.isCancellationRequested) return null
          const locations = data.locations.map(loc => {
            const startCol = loc.column || 1
            const nameLen = (loc.name || '').length || 1
            return {
              uri: monaco.Uri.parse('file:///workspace/' + tab.project + '/' + (loc.filePath || '')),
              range: new monaco.Range(loc.line, startCol, loc.line, startCol + nameLen)
            }
          })
          console.log('[goToImplementation] returning', locations.length, 'locations')
          return locations
        }
        console.log('[goToImplementation] no results found')
        appendConsole(t('noImplFound'), 'stderr')
      } catch (e) {
        console.error('[implementations] error:', e)
      }
      return null
    }
  })

  // Find All References (Shift+F12) — uses Monaco built-in peek UI
  monaco.languages.registerReferenceProvider('java', {
    provideReferences: async (model, position, context, token) => {
      const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
      if (!tab || !tab.project) return null
      try {
        const { data } = await http.post(`/workspace/projects/${tab.project}/navigate/references`, {
          filePath: tab.path, line: position.lineNumber, column: position.column
        })
        if (token.isCancellationRequested) return null
            if (data.success && data.locations && data.locations.length > 0) {
          await Promise.all(data.locations.map(loc =>
            ensureModelExists('file:///workspace/' + tab.project + '/' + (loc.filePath || ''), tab.project, loc.filePath || '')
          ))
          if (token.isCancellationRequested) return null
          const locations = data.locations.map(loc => {
            const filePath = loc.filePath || ''
            const startCol = loc.column || 1
            const endCol = startCol + Math.max((loc.name || '').length, 1)
            return {
              uri: monaco.Uri.parse('file:///workspace/' + tab.project + '/' + filePath),
              range: new monaco.Range(loc.line, startCol, loc.line, endCol)
            }
          })
          console.log('[findReferences] returning', locations.length, 'locations')
          return locations
        }
        console.log('[findReferences] no results found')
      } catch (e) {
        console.error('[references] error:', e)
      }
      return null
    }
  })

  // Handle Ctrl+Click navigation: open the target file in editor
  let navDisposable = null
  const editorActions = []
  function setupNavHandler() {
    if (navDisposable) navDisposable.dispose()
    navDisposable = editor.onMouseDown(async (e) => {
      if (e.target && e.target.type === monaco.editor.MouseTargetType.CONTENT_TEXT
          && e.event && e.event.rightButton && e.target.position) {
        editor.setPosition(e.target.position)
      }

      // Ctrl+Click on a definition link
      if (e.target && e.target.type === monaco.editor.MouseTargetType.CONTENT_TEXT
          && e.event && (e.event.ctrlKey || e.event.metaKey)) {
        const position = e.target.position
        if (!position) return
        const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
        if (!tab || !tab.project) return
        try {
          const { data } = await http.post(`/workspace/projects/${tab.project}/navigate/definition`, {
            filePath: tab.path, line: position.lineNumber, column: position.column
          })
                if (data.success && data.locations && data.locations.length > 0) {
            const loc = data.locations[0]
            // 如果目标是当前文件，直接跳转行
            if (loc.filePath === tab.path) {
              editor.revealLineInCenter(loc.line)
              editor.setPosition({ lineNumber: loc.line, column: loc.column })
              return
            }
            // 打开目标文件
            const targetName = loc.filePath.includes('/') ? loc.filePath.substring(loc.filePath.lastIndexOf('/') + 1) : loc.filePath
            try {
              await onSelectFile({ project: tab.project, path: loc.filePath, name: targetName })
              await nextTick()
              if (editor && loc.line) {
                editor.revealLineInCenter(loc.line)
                editor.setPosition({ lineNumber: loc.line, column: loc.column || 1 })
              }
            } catch (err) {
              appendConsole(t('navigationError') + ': ' + (err.message || 'Unknown'), 'stderr')
            }
          }
        } catch (err) {
          appendConsole(t('navigationError') + ': ' + (err.message || 'Unknown'), 'stderr')
        }
      }
    })
  }
  setupNavHandler()

  // 注册 Code Action Provider（快速修复：自动生成未实现的方法等）
  monaco.languages.registerCodeActionProvider('java', {
    provideCodeActions: async (model, range, context) => {
      if (!lspConnection || !isCurrentTabJava()) return { actions: [], dispose: () => {} }
      const diagnostics = context.markers.map(m => ({
        range: { start: { line: m.startLineNumber - 1, character: m.startColumn - 1 },
                 end: { line: m.endLineNumber - 1, character: m.endColumn - 1 } },
        message: m.message, severity: m.severity === monaco.MarkerSeverity.Error ? 1 : 2,
        source: m.source || ''
      }))
      try {
        const result = await lspConnection.sendRequest('textDocument/codeAction', {
          textDocument: { uri: getCurrentDocUri() },
          range: { start: { line: range.startLineNumber - 1, character: range.startColumn - 1 },
                   end: { line: range.endLineNumber - 1, character: range.endColumn - 1 } },
          context: { diagnostics }
        })
        if (!result || !Array.isArray(result)) return { actions: [], dispose: () => {} }
        const actions = result.map(ca => {
          const action = {
            title: ca.title,
            kind: 'quickfix',
            diagnostics: context.markers,
            isPreferred: true
          }
          if (ca.edit && ca.edit.changes) {
            const edits = []
            for (const [uri, fileEdits] of Object.entries(ca.edit.changes)) {
              for (const e of fileEdits) {
                edits.push({
                  resource: model.uri,
                  versionId: undefined,
                  textEdit: {
                    range: new monaco.Range(
                      e.range.start.line + 1, e.range.start.character + 1,
                      e.range.end.line + 1, e.range.end.character + 1),
                    text: e.newText
                  }
                })
              }
            }
            action.edit = { edits }
          }
          return action
        })
        return { actions, dispose: () => {} }
      } catch (e) {
        // 连接断开时静默忽略，不打印错误
        if (!lspConnection || (e && e.message && e.message.includes('disposed'))) return { actions: [], dispose: () => {} }
        console.warn('Code action failed', e)
        return { actions: [], dispose: () => {} }
      }
    }
  })

  // 重新注册编辑器右键菜单 actions（语言切换时调用）
  function registerEditorActions() {
    // 清理旧的 actions
    editorActions.forEach(d => d.dispose())
    editorActions.length = 0

    editorActions.push(editor.addAction({
      id: 'go-to-line',
      label: t('goToLine'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyG],
      contextMenuGroupId: '1_navigation',
      contextMenuOrder: 0.5,
      run: (ed) => {
        const model = ed.getModel()
        if (!model) return
        const totalLines = model.getLineCount()
        openDialog(t('goToLineTitle'), t('goToLinePlaceholder', { total: totalLines }), '', () => {
          const raw = dialog.value.trim()
          if (!raw) { dialog.visible = false; return }
          if (!/^\d+$/.test(raw)) {
            alert(t('goToLineInvalid'))
            dialog.value = ''
            return
          }
          const lineNum = parseInt(raw, 10)
          if (lineNum < 1 || lineNum > totalLines) {
            alert(t('goToLineOutOfRange', { total: totalLines }))
            dialog.value = ''
            return
          }
          dialog.visible = false
          ed.revealLineInCenter(lineNum)
          ed.setPosition({ lineNumber: lineNum, column: 1 })
          ed.focus()
        })
      }
    }))

    editorActions.push(editor.addAction({ id: 'run-code', label: t('runCode'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.F11], contextMenuGroupId: 'navigation', contextMenuOrder: 0, run: () => doSubmit() }))

    // Debug shortcuts
    editorActions.push(editor.addAction({ id: 'debug-start', label: t('debugStart'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.F5], contextMenuGroupId: 'navigation', contextMenuOrder: 0.2, run: () => doDebugStart() }))
    editorActions.push(editor.addAction({ id: 'debug-step-into', label: t('debugStepInto'), keybindings: [monaco.KeyCode.F5], contextMenuGroupId: 'navigation', contextMenuOrder: 0.3, run: () => doDebugStepInto() }))
    editorActions.push(editor.addAction({ id: 'debug-step-over', label: t('debugStepOver'), keybindings: [monaco.KeyCode.F6], contextMenuGroupId: 'navigation', contextMenuOrder: 0.4, run: () => doDebugStepOver() }))
    editorActions.push(editor.addAction({ id: 'debug-step-out', label: t('debugStepOut'), keybindings: [monaco.KeyCode.F7], contextMenuGroupId: 'navigation', contextMenuOrder: 0.5, run: () => doDebugStepOut() }))
    editorActions.push(editor.addAction({ id: 'debug-continue', label: t('debugContinue'), keybindings: [monaco.KeyCode.F8], contextMenuGroupId: 'navigation', contextMenuOrder: 0.6, run: () => doDebugContinue() }))
    editorActions.push(editor.addAction({ id: 'toggle-breakpoint', label: t('debugBreakpoint'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyB], contextMenuGroupId: 'navigation', contextMenuOrder: 0.7, run: () => toggleBreakpoint() }))

    editorActions.push(editor.addAction({ id: 'run-current-main', label: t('runCurrentMain'), contextMenuGroupId: 'navigation', contextMenuOrder: 0.1, run: () => runCurrentFileMain() }))

    editorActions.push(editor.addAction({ id: 'generate-getter-setter', label: t('generateGetterSetter'), contextMenuGroupId: '2_generate', contextMenuOrder: 1, run: (ed) => { if (isCurrentTabJava()) generateGetterSetter(ed) } }))

    editorActions.push(editor.addAction({ id: 'generate-constructor', label: t('generateConstructor'), contextMenuGroupId: '2_generate', contextMenuOrder: 2, run: (ed) => { if (isCurrentTabJava()) generateConstructor(ed) } }))

    editorActions.push(editor.addAction({ id: 'generate-serial-version-uid', label: t('generateSerialVersionUID'), contextMenuGroupId: '2_generate', contextMenuOrder: 3, run: (ed) => { if (isCurrentTabJava()) generateSerialVersionUID(ed) } }))

    editorActions.push(editor.addAction({ id: 'generate-override-methods', label: t('generateOverride'), contextMenuGroupId: '2_generate', contextMenuOrder: 4, run: (ed) => { if (isCurrentTabJava()) generateOverrideMethods(ed) } }))

    editorActions.push(editor.addAction({ id: 'generate-tostring', label: t('generateToString'), contextMenuGroupId: '2_generate', contextMenuOrder: 5, run: (ed) => { if (isCurrentTabJava()) generateToString(ed) } }))

    editorActions.push(editor.addAction({ id: 'generate-equals-hashcode', label: t('generateEqualsHashCode'), contextMenuGroupId: '2_generate', contextMenuOrder: 6, run: (ed) => { if (isCurrentTabJava()) generateEqualsHashCode(ed) } }))

    editorActions.push(editor.addAction({ id: 'search-files', label: t('searchFiles'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyP], run: () => openSearch('file') }))

    editorActions.push(editor.addAction({ id: 'search-symbols', label: t('searchSymbols'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyMod.Shift | monaco.KeyCode.KeyO], run: () => openSearch('symbol') }))

    editorActions.push(editor.addAction({ id: 'search-all', label: t('globalSearch'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyT], run: () => openSearch('all') }))

    editorActions.push(editor.addAction({ id: 'quick-fix', label: t('quickFix'), keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.Period], run: (ed) => { ed.getAction('editor.action.quickFix').run() } }))

    // Custom Go to Implementation (Ctrl+F12) — direct navigation without peek widget
    editorActions.push(editor.addAction({
      id: 'go-to-implementation-custom',
      label: t('goToImpl'),
      keybindings: [monaco.KeyMod.CtrlCmd | monaco.KeyCode.F12],
      contextMenuGroupId: '1_navigation',
      contextMenuOrder: 1.4,
      run: async (ed) => {
        const position = ed.getPosition()
        const model = ed.getModel()
        if (!position || !model) return
        const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
        if (!tab || !tab.project) return
        try {
          const { data } = await http.post(`/workspace/projects/${tab.project}/navigate/implementations`, {
            filePath: tab.path, line: position.lineNumber, column: position.column
          })
                if (data.success && data.locations && data.locations.length > 0) {
            const loc = data.locations[0]
            handleWorkspaceNavigation('file:///workspace/' + tab.project + '/' + loc.filePath, loc.line, loc.column || 1)
            if (data.locations.length > 1) {
              appendConsole(t('foundImpl', { count: data.locations.length }) + ': ' +
                data.locations.slice(1).map(l => l.filePath + ':' + l.line).join(', '), 'info')
            }
          } else {
            appendConsole(t('noImplFound'), 'stderr')
          }
        } catch (e) {
          appendConsole(t('navigationError') + ': ' + e.message, 'stderr')
        }
      }
    }))
  }

  /**
   * Localize built-in Monaco editor context menu items.
   * Uses a MutationObserver to intercept Monaco's context menu DOM and replace
   * English labels with localized versions. This is the most reliable approach
   * since Monaco captures action labels at registration time.
   */
  let contextMenuObserver = null
  function localizeEditorActions() {
    // Dispose previous observer
    if (contextMenuObserver) { contextMenuObserver.disconnect(); contextMenuObserver = null }

    // Map from English label text (Monaco built-in) to localized text
    const labelMap = {
      'Copy': t('copy'),
      'Cut': t('cut'),
      'Paste': t('paste'),
      'Change All Occurrences': t('changeAllOccurrences'),
      'Go to Definition': t('goToDefinition'),
      'Go to Implementation': t('goToImpl'),
      'Go to Implementations': t('goToImpl'),
      'Go to References': t('findAllRefs'),
      'References': t('findAllRefs'),
      'Go to Type Definition': t('goToImpl'),
      'Find All References': t('findAllRefs'),
      'Find All Implementations': t('goToImpl'),
      'Peek Definition': t('peekDefinition'),
      'Peek Implementation': t('peekImplementation'),
      'Peek Implementations': t('peekImplementation'),
      'Peek References': t('peekReferences'),
      'Peek Type Definition': t('peekImplementation'),
    }

    // Action ID -> localized label mapping — use Object.defineProperty to bypass
    // potential getter-only label property on Monaco's built-in action objects
    const actionLabelMap = {
      'editor.action.clipboardCopyAction': t('copy'),
      'editor.action.clipboardCutAction': t('cut'),
      'editor.action.clipboardPasteAction': t('paste'),
      'editor.action.changeAll': t('changeAllOccurrences'),
      'editor.action.revealDefinition': t('goToDefinition'),
      'editor.action.revealDefinitionAside': t('peekDefinition'),
      'editor.action.peekDefinition': t('peekDefinition'),
      'editor.action.goToImplementation': t('goToImpl'),
      'editor.action.goToImplementation.contains': t('goToImpl'),
      'editor.action.peekImplementation': t('peekImplementation'),
      'editor.action.goToReferences': t('findAllRefs'),
      'editor.action.referenceSearch.trigger': t('peekReferences'),
      'editor.action.peekReferences': t('peekReferences'),
      'editor.action.peekTypeDefinition': t('peekImplementation'),
      'editor.action.goToTypeDefinition': t('goToImpl'),
    }

    // Override built-in action labels via Object.defineProperty
    if (editor) {
      try {
        const actions = editor.getActions()
        if (actions) {
          actions.forEach(action => {
            const localized = actionLabelMap[action.id]
            if (localized) {
              try {
                Object.defineProperty(action, 'label', {
                  value: localized,
                  writable: true,
                  configurable: true
                })
              } catch (e2) { /* defineProperty may fail on some action types */ }
            }
          })
        }
      } catch (e) { /* editor.getActions() may not be available */ }
    }

    function localizeElement(el) {
      const text = (el.textContent || '').trim()
      const title = (el.getAttribute('title') || '').trim()
      const ariaLabel = (el.getAttribute('aria-label') || '').trim()
      if (labelMap[text] && el.textContent !== labelMap[text]) el.textContent = labelMap[text]
      if (labelMap[title]) el.setAttribute('title', labelMap[title])
      if (labelMap[ariaLabel]) el.setAttribute('aria-label', labelMap[ariaLabel])
    }

    // DOM observer: when context menu appears, localize action label elements.
    // Monaco renders context menus and peek widgets at the document body level.
    // The menu containers are: .monaco-menu-container (context menu) and .peekview-widget (peek results)
    let localizeTimer = null
    contextMenuObserver = new MutationObserver(() => {
      if (localizeTimer) clearTimeout(localizeTimer)
      localizeTimer = setTimeout(() => {
        // Look across all containers: Monaco context menu + peek widget
        let found = false
        const containers = document.querySelectorAll('.monaco-menu-container, .monaco-menu')
        containers.forEach(menu => {
          found = true
          menu.querySelectorAll('.action-label').forEach(localizeElement)
        })
        // Also check peek widget (if visible)
        const peekContainer = document.querySelector('.monaco-editor .peekview-widget')
        if (peekContainer) {
          found = true
          peekContainer.querySelectorAll('.action-label, .monaco-icon-label').forEach(localizeElement)
        }
        if (!found) return // no menu visible, this was an unrelated DOM change
      }, 100)
    })
    contextMenuObserver.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['title', 'aria-label'] })
  }

  registerEditorActions()
  localizeEditorActions()

  // 语言切换时重新注册 editor actions 并重新本地化
  onLangChange(() => { if (editor) { registerEditorActions(); localizeEditorActions() } })

  await checkAuth()
  document.addEventListener('click', hideContextMenu)

  // Check if there's a running debug session on the backend (e.g. after browser refresh)
  try {
    const { data } = await http.get('/workspace/debug/status')
    if (data.success && data.running) {
      debugReconnectAvailable.value = true
    }
  } catch {}

  // Check if there's a running deploy on the backend (e.g. after browser refresh)
  try {
    const { data } = await http.get('/workspace/deploy/running')
    if (data.success && data.running?.length > 0) {
      const current = data.running.find(d => d.projectName === activeProject.value)
      if (current) deployReconnecting.value = true
    }
  } catch {}


  // 切换项目时自动刷新右侧 Maven/Gradle 面板数据
  watch(activeProject, () => {
    if (rightPanel.value === 'maven' && activeProjectType.value === 'maven') {
      loadMavenPanel()
    } else if (rightPanel.value === 'gradle' && activeProjectType.value === 'gradle') {
      loadGradlePanel()
    }
    if (rightPanel.value === 'maven' && activeProjectType.value !== 'maven') {
      rightPanel.value = ''
    } else if (rightPanel.value === 'gradle' && activeProjectType.value !== 'gradle') {
      rightPanel.value = ''
    }
  })

  // 切换到终端 tab 或调整终端大小时重新适配 xterm
  watch(consoleTab, (tab) => {
    if (tab === 'terminal' && xtermTerminalRef.value) {
      nextTick(() => xtermTerminalRef.value.scheduleTerminalFit())
    }
  })
  watch(consoleHeight, () => {
    if (consoleTab.value === 'terminal' && xtermTerminalRef.value) {
      nextTick(() => xtermTerminalRef.value.scheduleTerminalFit())
    }
  })
  window.addEventListener('beforeunload', onBeforeUnload)
})

onBeforeUnmount(() => {
  if (editorStateSyncTimer) { clearInterval(editorStateSyncTimer); editorStateSyncTimer = null }
  // 保存所有未保存的 tab
  if (autoSaveTimer) { clearTimeout(autoSaveTimer); autoSaveTimer = null }
  for (const tab of openTabs.value) {
    if (dirtyTabs.value[tab.key]) silentSave(tab.key)
  }
  document.removeEventListener('click', hideContextMenu)
  window.removeEventListener('beforeunload', onBeforeUnload)
  if (lspReconnectTimer) { clearTimeout(lspReconnectTimer); lspReconnectTimer = null }
  if (lspConnection) { lspConnection.dispose(); lspConnection = null }
  if (lspWebSocket) { lspWebSocket.close(); lspWebSocket = null }
  // Debug session survives browser close — do NOT auto-stop
  abortDebugReader = true
  if (editor) { editor.dispose(); editor = null }
})

// 浏览器关闭/刷新前保存
function onBeforeUnload() {
  if (currentTab.value && editor) {
    tabContents.value = { ...tabContents.value, [currentTab.value]: editor.getValue() }
  }
  for (const tab of openTabs.value) {
    if (dirtyTabs.value[tab.key]) {
      const content = tabContents.value[tab.key]
      if (content !== undefined) {
        // 同步 XHR 确保关闭前保存完成
        try {
          const xhr = new XMLHttpRequest()
          xhr.open('PUT', `/workspace/projects/${tab.project}/file`, false) // 同步
          xhr.setRequestHeader('Content-Type', 'application/json')
          xhr.send(JSON.stringify({ path: tab.path, content }))
        } catch (e) { /* 忽略 */ }
      }
    }
  }
  // Debug session survives browser close — do NOT auto-stop
}

// ==================== Run Code ====================
async function doSubmit(overrideMainClass) {
  if (loading.value) return
  if (!editor) return
  const source = editor.getValue()
  if (!source.trim()) return

  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (!tab || !tab.project) {
    appendConsole('> ❌ ' + t('openProjectFileFirst'), 'stderr')
    consoleTab.value = 'console'
    return
  }

  // 先保存当前文件
  await http.put(`/workspace/projects/${tab.project}/file`, { path: tab.path, content: source }).catch(() => {})
  dirtyTabs.value = { ...dirtyTabs.value, [currentTab.value]: false }
  if (autoSaveTimer) { clearTimeout(autoSaveTimer); autoSaveTimer = null }

  loading.value = true; consoleTab.value = 'console'
  const mainClass = String(overrideMainClass || runConfig.mainClass || guessMainClass(tab.path, source) || 'Main')
  const projectType = activeProjectType.value

  // Maven/Gradle 项目使用外部进程运行（支持 Spring Boot 等）
  if (projectType === 'maven' || projectType === 'gradle') {
    appendConsole('> ' + t('runProjectMsg', { project: tab.project, mainClass }), 'info')
    try {
      await streamRun(tab.project, mainClass)
    } finally {
      loading.value = false
    }
  } else {
    // Plain 项目仍用 ECJ 编译 + 反射运行
    appendConsole('> ' + t('compilingMsg', { version: ideSettings.jdkVersion }), 'info')
    appendConsole('> ' + t('projectCompileMsg', { project: tab.project, mainClass }), 'info')
    const params = new URLSearchParams()
    params.append('jdkVersion', String(ideSettings.jdkVersion))
    params.append('projectName', tab.project)
    params.append('mainClass', mainClass)
    if (runConfig.timeLimit) params.append('executeTimeLimit', runConfig.timeLimit)
    if (runConfig.programArgs.trim()) params.append('executeArgs', runConfig.programArgs.trim())
    if (runConfig.jvmArgs.trim()) params.append('jvmArgs', runConfig.jvmArgs.trim())
    try {
      const { data } = await http.post('/compile', params)
      if (data && data.message) { const isErr = data.type === 'error' || data.type === 'fail' || data.message.includes('错误') || data.message.includes('error'); appendConsole(data.message, isErr ? 'stderr' : 'info') }
      if (data && data.durationTime >= 0) appendConsole(`[运行耗时: ${data.durationTime}ms]`, 'info')
      if (data && data.result) appendConsole(data.result, 'stdout')
      if (data && !data.result && data.type !== 'error' && data.type !== 'fail') appendConsole(t('noOutput'), 'info')
      if (!data) appendConsole(t('requestFailed'), 'stderr')
    } catch (e) { appendConsole(t('requestFailed')  + ': ' + (e.response?.data?.message || e.message), 'stderr') }
    finally { loading.value = false }
  }
}

/** 通过外部进程运行 Maven/Gradle 项目（SSE 流式输出） */
function streamRun(projectName, mainClass) {
  return new Promise((resolve) => {
    const body = JSON.stringify({
      projectName: String(projectName || ''),
      mainClass: String(mainClass || 'Main'),
      jvmArgs: String(runConfig.jvmArgs || '').trim(),
      programArgs: String(runConfig.programArgs || '').trim()
    })
    // 同时通过 header 和 query parameter 传递 token，兼容代理环境
    const token = encodeURIComponent(authToken.value || '')
    fetch('/workspace/run?_token=' + token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-Auth-Token': authToken.value },
      body
    }).then(response => {
      if (!response.ok) {
        appendConsole('> ' + t('runRequestFailed') +': HTTP ' + response.status, 'stderr')
        loading.value = false
        resolve()
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function processChunk() {
        reader.read().then(({ done, value }) => {
          if (done) {
            if (buffer.trim()) processSSEBuffer(buffer)
            loading.value = false
            resolve()
            return
          }
          buffer += decoder.decode(value, { stream: true })
          const parts = buffer.split('\n\n')
          buffer = parts.pop() || ''
          for (const part of parts) {
            processSSEEvent(part)
          }
          processChunk()
        }).catch(err => {
          appendConsole('> ' + t('streamReadError') + ': ' + err.message, 'stderr')
          loading.value = false
          resolve()
        })
      }
      processChunk()
    }).catch(err => {
      appendConsole('> ' + t('runRequestFailed') + ': ' + err.message, 'stderr')
      loading.value = false
      resolve()
    })
  })
}

/** 停止当前运行的进程 */
async function doStop() {
  try {
    await http.post('/workspace/run/stop', { projectName: activeProject.value })
    appendConsole('> ' + t('processStopped'), 'info')
  } catch {}
  loading.value = false
}

/** 页面加载时检查后端是否有正在运行的进程，恢复 loading 状态 */
async function checkRunStatus() {
  try {
    const { data } = await http.get('/workspace/run/status')
    if (data.runningCount > 0) {
      loading.value = true
      appendConsole(t('processDetected'), 'info')
    }
  } catch {}
}

async function handleSave() {
  if (!editor) return; const content = editor.getValue()
  if (currentTab.value) {
    const tab = openTabs.value.find(t => t.key === currentTab.value)
    if (tab) {
      try {
        await http.put(`/workspace/projects/${tab.project}/file`, { path: tab.path, content })
        tabContents.value = { ...tabContents.value, [currentTab.value]: content }
        dirtyTabs.value = { ...dirtyTabs.value, [currentTab.value]: false }
        if (autoSaveTimer) { clearTimeout(autoSaveTimer); autoSaveTimer = null }
        appendConsole('> ' + t('fileSaved'), 'info')
        const fname = tab.path.split('/').pop()
        if (fname === 'pom.xml' || fname === 'build.gradle') {
          await loadProjects()
          if (expandedLibs.value[tab.project]) await loadLibs(tab.project)
        }
      } catch (e) { appendConsole('> ' + t('saveFailed') + ': ' + e.message, 'stderr') }
      return
    }
  }
  localStorage.setItem('javaCode', content); appendConsole('> ' + t('codeSavedLocal'), 'info')
}

function handleReset() { if (editor) { editor.setValue(DEFAULT_CODE); clearConsole(); problems.value = [] } }

// ==================== Java 代码生成 ====================

/** 解析 Java 源码中的字段列表 */
function parseJavaFields(source) {
  const fields = []
  // 匹配字段声明: (修饰符) 类型 名称 (= 值);
  const fieldRegex = /^\s*((?:private|protected|public|static|final|transient|volatile)\s+)*(\w[\w<>\[\],\s]*?)\s+(\w+)\s*(?:=\s*[^;]*)?\s*;/gm
  let m
  while ((m = fieldRegex.exec(source)) !== null) {
    const modifiers = (m[1] || '').trim()
    // 跳过 static 字段（类变量不需要实例 getter/setter）
    if (modifiers.includes('static')) continue
    const type = m[2].trim()
    const name = m[3].trim()
    // 排除方法内的局部变量（简单判断：字段应在类体内、方法体外）
    const before = source.substring(0, m.index)
    const braceDepth = (before.match(/\{/g) || []).length - (before.match(/\}/g) || []).length
    if (braceDepth === 1) { // 类体内第一层
      fields.push({ type, name, modifiers })
    }
  }
  return fields
}

/** 解析类名和父类/接口 */
function parseJavaClass(source) {
  const classMatch = source.match(/(?:public\s+)?(?:abstract\s+)?class\s+(\w+)(?:\s+extends\s+(\w+))?(?:\s+implements\s+([\w,\s]+))?/)
  if (!classMatch) return null
  return {
    name: classMatch[1],
    superClass: classMatch[2] || null,
    interfaces: classMatch[3] ? classMatch[3].split(',').map(s => s.trim()) : []
  }
}

/** 找到类体最后一个 } 之前的插入位置 */
function findClassEndInsertLine(source) {
  const lines = source.split('\n')
  let braceDepth = 0
  let lastCloseBrace = lines.length - 1
  for (let i = 0; i < lines.length; i++) {
    for (const ch of lines[i]) {
      if (ch === '{') braceDepth++
      if (ch === '}') {
        braceDepth--
        if (braceDepth === 0) { lastCloseBrace = i; break }
      }
    }
    if (braceDepth === 0 && i > 0) break
  }
  return lastCloseBrace
}

/** 首字母大写 */
function capitalize(s) { return s.charAt(0).toUpperCase() + s.slice(1) }

/** 获取缩进（检测源码中的缩进风格） */
function detectIndent(source) {
  const m = source.match(/^( +|\t+)(?:private|protected|public)/m)
  return m ? m[1] : '    '
}

function generateGetterSetter(ed) {
  const source = ed.getValue()
  const fields = parseJavaFields(source)
  if (fields.length === 0) { alert(t('noFieldsFound')); return }
  const indent = detectIndent(source)
  const insertLine = findClassEndInsertLine(source)

  let code = '\n'
  for (const f of fields) {
    const cap = capitalize(f.name)
    const getter = f.type === 'boolean' ? 'is' + cap : 'get' + cap
    // 检查是否已存在
    if (source.includes(getter + '(')) continue
    code += `${indent}public ${f.type} ${getter}() {\n${indent}${indent}return this.${f.name};\n${indent}}\n\n`
    if (!source.includes('set' + cap + '(')) {
      code += `${indent}public void set${cap}(${f.type} ${f.name}) {\n${indent}${indent}this.${f.name} = ${f.name};\n${indent}}\n\n`
    }
  }
  if (code.trim() === '') { alert(t('allGetterSetterExist')); return }
  ed.executeEdits('generate', [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: code
  }])
}

function generateConstructor(ed) {
  const source = ed.getValue()
  const classInfo = parseJavaClass(source)
  if (!classInfo) { alert(t('noClassFound')); return }
  const fields = parseJavaFields(source)
  const indent = detectIndent(source)
  const insertLine = findClassEndInsertLine(source)

  let code = '\n'
  // 无参构造
  if (!source.includes(classInfo.name + '()')) {
    code += `${indent}public ${classInfo.name}() {\n${indent}}\n\n`
  }
  // 全参构造
  if (fields.length > 0) {
    const params = fields.map(f => f.type + ' ' + f.name).join(', ')
    const sig = classInfo.name + '(' + fields.map(f => f.type).join(', ') + ')'
    if (!source.includes(sig) && !source.includes(classInfo.name + '(' + params)) {
      code += `${indent}public ${classInfo.name}(${params}) {\n`
      for (const f of fields) {
        code += `${indent}${indent}this.${f.name} = ${f.name};\n`
      }
      code += `${indent}}\n\n`
    }
  }
  if (code.trim() === '') { alert(t('constructorExists')); return }
  ed.executeEdits('generate', [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: code
  }])
}

function generateSerialVersionUID(ed) {
  const source = ed.getValue()
  if (source.includes('serialVersionUID')) { alert(t('serialVersionUIDExists')); return }
  const classInfo = parseJavaClass(source)
  if (!classInfo) { alert(t('noClassFound')); return }
  const indent = detectIndent(source)
  // 在类体开头（第一个 { 之后）插入
  const lines = source.split('\n')
  let insertLine = -1
  for (let i = 0; i < lines.length; i++) {
    if (lines[i].includes('class ' + classInfo.name) && lines[i].includes('{')) {
      insertLine = i + 2 // { 所在行的下一行
      break
    }
    if (lines[i].includes('class ' + classInfo.name)) {
      // { 可能在下一行
      for (let j = i + 1; j < lines.length; j++) {
        if (lines[j].includes('{')) { insertLine = j + 2; break }
      }
      break
    }
  }
  if (insertLine === -1) { alert(t('noInsertPosition')); return }
  const uid = Math.floor(Math.random() * 9000000000000000000) + 1000000000000000000
  const code = `\n${indent}private static final long serialVersionUID = ${uid}L;\n`
  ed.executeEdits('generate', [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: code
  }])
}

async function generateOverrideMethods(ed) {
  const source = ed.getValue()
  const classInfo = parseJavaClass(source)
  if (!classInfo) { alert(t('noClassFound')); return }
  if (!classInfo.superClass && classInfo.interfaces.length === 0) {
    alert(t('noExtendsImplements')); return
  }

  const tab = openTabs.value.find(t => t.key === currentTab.value)
  if (!tab) { alert(t('openProjectFile')); return }

  try {
    const { data } = await http.post(`/workspace/projects/${tab.project}/generate-overrides`, { source })
    if (!data.success) { alert(data.message || t('generateOverrideFailed')); return }
    const methods = data.methods || []
    if (methods.length === 0) { alert(t('allOverrideExist')); return }

    // 从后往前插入，避免偏移量错乱
    const edits = methods.map(m => {
      // insertOffset 是字符偏移量，转换为行列
      const pos = ed.getModel().getPositionAt(m.insertOffset)
      return {
        range: new monaco.Range(pos.lineNumber, pos.column, pos.lineNumber, pos.column),
        text: '\n' + m.code
      }
    })
    ed.executeEdits('generate-overrides', edits)
  } catch (e) {
    alert(t('generateOverrideFailed') + ': ' + (e.message || e))
  }
}

function generateToStringBody(className, fields, indent) {
  let body = `${indent}@Override\n${indent}public String toString() {\n`
  if (fields.length === 0) {
    body += `${indent}${indent}return "${className}{}";\n`
  } else {
    body += `${indent}${indent}return "${className}{" +\n`
    fields.forEach((f, i) => {
      const prefix = i === 0 ? '' : ', '
      body += `${indent}${indent}${indent}"${prefix}${f.name}=" + ${f.name} +\n`
    })
    body += `${indent}${indent}${indent}"}";\n`
  }
  body += `${indent}}\n\n`
  return body
}

function generateToString(ed) {
  const source = ed.getValue()
  if (source.includes('toString()')) { alert(t('toStringExists')); return }
  const classInfo = parseJavaClass(source)
  if (!classInfo) { alert(t('noClassFound')); return }
  const fields = parseJavaFields(source)
  const indent = detectIndent(source)
  const insertLine = findClassEndInsertLine(source)
  const code = '\n' + generateToStringBody(classInfo.name, fields, indent)
  ed.executeEdits('generate', [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: code
  }])
}

function generateEqualsHashCode(ed) {
  const source = ed.getValue()
  const classInfo = parseJavaClass(source)
  if (!classInfo) { alert(t('noClassFound')); return }
  const fields = parseJavaFields(source)
  const indent = detectIndent(source)
  const insertLine = findClassEndInsertLine(source)
  const i2 = indent + indent

  let code = '\n'
  if (!source.includes('equals(')) {
    code += `${indent}@Override\n${indent}public boolean equals(Object o) {\n`
    code += `${i2}if (this == o) return true;\n`
    code += `${i2}if (o == null || getClass() != o.getClass()) return false;\n`
    code += `${i2}${classInfo.name} that = (${classInfo.name}) o;\n`
    if (fields.length === 0) {
      code += `${i2}return true;\n`
    } else {
      const checks = fields.map(f => {
        if (['int','long','short','byte','char','boolean','float','double'].includes(f.type)) {
          return `${f.name} == that.${f.name}`
        }
        return `java.util.Objects.equals(${f.name}, that.${f.name})`
      })
      code += `${i2}return ${checks.join(' &&\n' + i2 + indent)};\n`
    }
    code += `${indent}}\n\n`
  }
  if (!source.includes('hashCode()')) {
    code += `${indent}@Override\n${indent}public int hashCode() {\n`
    if (fields.length === 0) {
      code += `${i2}return super.hashCode();\n`
    } else {
      code += `${i2}return java.util.Objects.hash(${fields.map(f => f.name).join(', ')});\n`
    }
    code += `${indent}}\n\n`
  }
  if (code.trim() === '') { alert(t('equalsHashCodeExists')); return }
  ed.executeEdits('generate', [{
    range: new monaco.Range(insertLine, 1, insertLine, 1),
    text: code
  }])
}

// ==================== Maven / Gradle Build ====================
async function doMavenBuild(goal) {
  if (!activeProject.value) {  alert(t('selectProject')); return }
  loading.value = true; consoleTab.value = 'console'
  appendConsole(`> ${t('maven')} ${goal}...`, 'info')
  await streamBuild('maven', goal)
}

async function doGradleBuild(task) {
  if (!activeProject.value) {  alert(t('selectProject')); return }
  loading.value = true; consoleTab.value = 'console'
  appendConsole(`> ${t('gradle')} ${task}...`, 'info')
  await streamBuild('gradle', task)
}

/** SSE 流式构建，逐行输出到 console */
function streamBuild(buildTool, goal) {
  return new Promise((resolve) => {
    const body = JSON.stringify({ projectName: activeProject.value, buildTool, goal })
    fetch('/workspace/build', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-Auth-Token': authToken.value },
      body
    }).then(response => {
      if (!response.ok) {
        appendConsole('> ' + t('buildRequestFailed') + ': HTTP ' + response.status, 'stderr')
        loading.value = false
        resolve()
        return
      }
      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ''

      function processChunk() {
        reader.read().then(({ done, value }) => {
          if (done) {
            // 处理 buffer 中剩余数据
            if (buffer.trim()) processSSEBuffer(buffer)
            loading.value = false
            resolve()
            return
          }
          buffer += decoder.decode(value, { stream: true })
          // 按双换行分割 SSE 事件
          const parts = buffer.split('\n\n')
          buffer = parts.pop() || ''
          for (const part of parts) {
            processSSEEvent(part)
          }
          processChunk()
        }).catch(err => {
          appendConsole('> ' + t('streamReadError') + ': ' + err.message, 'stderr')
          loading.value = false
          resolve()
        })
      }
      processChunk()
    }).catch(err => {
      appendConsole('> ' + t('buildRequestFailed') +': ' + err.message, 'stderr')
      loading.value = false
      resolve()
    })
  })
}

function processSSEBuffer(text) {
  const events = text.split('\n\n')
  for (const evt of events) {
    if (evt.trim()) processSSEEvent(evt)
  }
}

/** 构建过程中收集的最后几条错误行 */
let buildErrorLines = []

function processSSEEvent(raw) {
  let eventName = 'message'
  let data = ''
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) eventName = line.substring(6).trim()
    else if (line.startsWith('data:')) data = line.substring(5)
  }
  if (eventName === 'line') {
    const isErr = /\[ERROR\]|FAILED|BUILD FAILURE|Exception|错误/.test(data)
    const isWarn = /\[WARNING\]|WARN/.test(data)
    if (isErr) {
      const cleaned = data.replace(/^\[ERROR\]\s*/, '').trim()
      if (cleaned && !cleaned.startsWith('->') && !cleaned.startsWith('For more information')) {
        buildErrorLines.push(cleaned)
        if (buildErrorLines.length > 5) buildErrorLines.shift()
      }
    }
    appendConsole(data, isErr ? 'stderr' : isWarn ? 'info' : 'stdout')
  } else if (eventName === 'error') {
    appendConsole('> ' + data, 'stderr')
  } else if (eventName === 'done') {
    try {
      const info = JSON.parse(data)
      if (info.exitCode === 0) {
        appendConsole('> ' + t('buildSuccess'), 'info')
        if (lspConnection && editor && isCurrentTabJava()) {
          sendDidChange()
        }
      } else {
        const reason = buildErrorLines.length > 0
          ? buildErrorLines[buildErrorLines.length - 1]
          : ''
        appendConsole(`> BUILD FAILED (exit code: ${info.exitCode})` + (reason ? ': ' + reason : ''), 'stderr')
      }
    } catch {
      appendConsole('> ' + t('buildComplete'), 'info')
    }
    buildErrorLines = []
  }
}

async function detectMainClasses() {
  if (!activeProject.value) { alert(t('selectProject')); return }
  try {
    const { data } = await http.get('/detect-main-classes', { params: { projectName: activeProject.value } })
    if (data.success && data.mainClasses.length > 0) {
      if (data.mainClasses.length === 1) {
        runConfig.mainClass = data.mainClasses[0]
        detectedMainClasses.value = []
      } else {
        detectedMainClasses.value = data.mainClasses
      }
    } else {
      alert(t('noMainClassFound'))
      detectedMainClasses.value = []
    }
  } catch { alert(t('detectFailed')) }
}

/** 检测并设置主类（运行配置面板 Maven/Gradle 区域用） */
async function detectAndSetMainClass() {
  if (!activeProject.value) {  alert(t('selectProject')); return }
  try {
    const { data } = await http.get('/detect-main-classes', { params: { projectName: activeProject.value } })
    if (data.success && data.mainClasses && data.mainClasses.length > 0) {
      if (data.mainClasses.length === 1) {
        runConfig.mainClass = data.mainClasses[0]
        appendConsole('> ' + t('detectedMainClass') + ': ' + runConfig.mainClass, 'info')
      } else {
        detectedMainClasses.value = data.mainClasses
        appendConsole('> ' + t('detectedMultipleMain', { count: data.mainClasses.length }), 'info')
      }
    } else {
      appendConsole('> ❌ ' + t('noMainClassFound'), 'stderr')
      consoleTab.value = 'console'
    }
  } catch { appendConsole('> ' + t('detectFailed'), 'stderr') }
}

/** 运行当前文件的 main 方法（右键菜单用） */
async function runCurrentFileMain() {
  if (!editor) return
  const source = editor.getValue()
  // 检查当前文件是否包含 main 方法
  if (!/public\s+static\s+void\s+main\s*\(\s*String\s*\[\s*\]/.test(source)) {
    appendConsole('> ❌ ' + t('noMainMethod'), 'stderr')
    consoleTab.value = 'console'
    return
  }
  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (tab) {
    const mainClass = guessMainClass(tab.path, source)
    await doSubmit(mainClass)
  } else {
    await doSubmit()
  }
}

/** 重新编译：清除编译产物后重新编译运行 */
async function doRebuild() {
  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (!tab || !tab.project) {
    appendConsole('> ❌ ' + t('openProjectFileFirstShort'), 'stderr')
    consoleTab.value = 'console'
    return
  }
  loading.value = true
  consoleTab.value = 'console'
  appendConsole('> ' + t('cleaningBuild'), 'info')
  try {
    const { data } = await http.post(`/workspace/projects/${tab.project}/clean-build`)
    if (data.success) {
      appendConsole('> ' + t('cleaned') + ': ' + (data.deleted || []).join(', '), 'info')
    } else {
      appendConsole('> ' + t('cleanFailed') + ': ' + (data.message || ''), 'stderr')
    }
  } catch (e) {
    appendConsole('> ' + t('cleanRequestFailed') + ': ' + e.message, 'stderr')
  }
  loading.value = false
  appendConsole('> ' + t('recompiling'), 'info')
  await doSubmit()
}

// ==================== Debug Functions ====================

let abortDebugReader = false
let debugActionLock = false  // Prevent concurrent debug actions (step/continue/resume)

/** Start debug session via SSE */
async function doDebugStart() {
  if (debugState.value !== 'disconnected') return
  if (!editor) return

  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (!tab || !tab.project) {
    appendConsole('> ❌ ' + t('openProjectFileFirst'), 'stderr')
    consoleTab.value = 'console'
    return
  }

  // Save current file first
  const source = editor.getValue()
  await http.put(`/workspace/projects/${tab.project}/file`, { path: tab.path, content: source }).catch(() => {})

  const mainClass = String(runConfig.mainClass || guessMainClass(tab.path, source) || 'Main')
  appendConsole('> 🐛 ' + t('debugStart') + ': ' + tab.project + ' / ' + mainClass, 'info')
  consoleTab.value = 'console'
  debugState.value = 'running'
  debugReconnectAvailable.value = false
  debugStackFrames.value = []
  debugVariables.value = []

  const projectType = activeProjectType.value
  const launchMode = projectType === 'maven' ? 'MAVEN' : (projectType === 'gradle' ? 'GRADLE' : 'MAIN_CLASS')

  const body = JSON.stringify({
    projectName: String(tab.project || ''),
    launchMode: launchMode,
    mainClass: String(mainClass || 'Main'),
    jvmArgs: String(runConfig.jvmArgs || '').trim(),
    programArgs: String(runConfig.programArgs || '').trim(),
    autoCompile: 'true',
    suspend: 'true'
  })
  const token = encodeURIComponent(authToken.value || '')
  abortDebugReader = false

  try {
    const response = await fetch('/workspace/debug/start?_token=' + token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-Auth-Token': authToken.value },
      body
    })
    if (!response.ok) {
      appendConsole('> ❌ ' + t('runRequestFailed') + ': HTTP ' + response.status, 'stderr')
      debugState.value = 'disconnected'
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (!abortDebugReader) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        processDebugEvent(part)
      }
    }
  } catch (err) {
    if (!abortDebugReader) {
      appendConsole('> ❌ ' + t('runRequestFailed') + ': ' + err.message, 'stderr')
    }
  }
  if (debugState.value !== 'disconnected') {
    debugState.value = 'disconnected'
    clearDebugHighlights()
    appendConsole('> ' + t('debugSessionEnd'), 'info')
  }
}

/** Stop debug session */
async function doDebugStop() {
  abortDebugReader = true
  debugActionLock = false
  stepPending = false
  try {
    await http.post('/workspace/debug/stop')
  } catch {}
  debugState.value = 'disconnected'
  stepPending = false
  debugReconnectAvailable.value = false
  debugStackFrames.value = []
  debugVariables.value = []
  debugThreadId.value = 0
  clearDebugHighlights()
  appendConsole('> ' + t('debugStopped'), 'info')
}

/** Continue execution */
async function doDebugContinue() {
  if (debugActionLock) return
  debugActionLock = true
  stepPending = true  // prevent step clicks while running freely
  try {
    await http.post('/workspace/debug/continue')
    debugState.value = 'running'
    debugStackFrames.value = []
    debugVariables.value = []
    clearDebugHighlights()
  } catch (e) {
    stepPending = false
    appendConsole('> ❌ ' + t('requestFailed') + ': ' + e.message, 'stderr')
  } finally {
    debugActionLock = false
  }
}

/** Step over */
async function doDebugStepOver() {
  if (stepPending || debugActionLock) return
  debugActionLock = true
  stepPending = true
  try {
    await http.post('/workspace/debug/stepOver')
    debugState.value = 'running'
    debugStackFrames.value = []
    debugVariables.value = []
    clearDebugHighlights()
  } catch (e) {
    stepPending = false
    appendConsole('> ❌ ' + t('requestFailed') + ': ' + e.message, 'stderr')
  } finally {
    debugActionLock = false
  }
}

/** Step into */
async function doDebugStepInto() {
  if (stepPending || debugActionLock) return
  debugActionLock = true
  stepPending = true
  try {
    await http.post('/workspace/debug/stepInto')
    debugState.value = 'running'
    debugStackFrames.value = []
    debugVariables.value = []
    clearDebugHighlights()
  } catch (e) {
    stepPending = false
    appendConsole('> ❌ ' + t('requestFailed') + ': ' + e.message, 'stderr')
  } finally {
    debugActionLock = false
  }
}

/** Step out */
async function doDebugStepOut() {
  if (stepPending || debugActionLock) return
  debugActionLock = true
  stepPending = true
  try {
    await http.post('/workspace/debug/stepOut')
    debugState.value = 'running'
    debugStackFrames.value = []
    debugVariables.value = []
    clearDebugHighlights()
  } catch (e) {
    stepPending = false
    appendConsole('> ❌ ' + t('requestFailed') + ': ' + e.message, 'stderr')
  } finally {
    debugActionLock = false
  }
}

/** Reconnect to a running debug session after browser close/refresh */
async function doDebugReconnect() {
  if (debugState.value !== 'disconnected') return
  if (!debugReconnectAvailable.value) return

  appendConsole('> 🔌 ' + t('debugReconnect') + '...', 'info')
  consoleTab.value = 'console'
  debugState.value = 'running'
  debugReconnectAvailable.value = false
  debugStackFrames.value = []
  debugVariables.value = []

  const token = encodeURIComponent(authToken.value || '')
  abortDebugReader = false

  try {
    const response = await fetch('/workspace/debug/reconnect?_token=' + token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'Accept': 'text/event-stream', 'X-Auth-Token': authToken.value }
    })
    if (!response.ok) {
      appendConsole('> ❌ ' + t('runRequestFailed') + ': HTTP ' + response.status, 'stderr')
      debugState.value = 'disconnected'
      debugReconnectAvailable.value = false
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (!abortDebugReader) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        processDebugEvent(part)
      }
    }
  } catch (err) {
    if (!abortDebugReader) {
      appendConsole('> ❌ ' + t('debugReconnectingFailed') + ': ' + err.message, 'stderr')
    }
  }
  if (debugState.value !== 'disconnected') {
    debugState.value = 'disconnected'
    debugReconnectAvailable.value = false
    clearDebugHighlights()
    appendConsole('> ' + t('debugSessionEnd'), 'info')
  }
}

/** Reconnect to a running deploy session after browser close/refresh */
async function doDeployReconnect() {
  if (!deployReconnecting.value) return
  if (!xtermTerminalRef.value) return

  appendConsole('> 🔌 ' + t('deployReconnect') + '...', 'info')
  consoleTab.value = 'console'
  deployReconnecting.value = false
  xtermTerminalRef.value.deployRunning = true

  try {
    const token = encodeURIComponent(authToken.value || '')
    const params = new URLSearchParams()
    params.append('projectName', activeProject.value)
    const response = await fetch('/workspace/deploy/reconnect?_token=' + token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'Accept': 'text/event-stream', 'X-Auth-Token': authToken.value },
      body: params
    })
    if (!response.ok) {
      appendConsole('> ❌ ' + t('runRequestFailed') + ': HTTP ' + response.status, 'stderr')
      xtermTerminalRef.value.deployRunning = false
      return
    }
    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const parts = buffer.split('\n\n')
      buffer = parts.pop() || ''
      for (const part of parts) {
        if (!part.trim()) continue
        let eventName = 'message'
        let data = ''
        for (const line of part.split('\n')) {
          if (line.startsWith('event: ')) eventName = line.substring(7)
          else if (line.startsWith('data: ')) data = line.substring(6)
        }
        if (eventName === 'ping') continue
        if (xtermTerminalRef.value.deployReconnectSseEvent) {
          xtermTerminalRef.value.deployReconnectSseEvent(eventName, data)
        }
      }
    }
  } catch (err) {
    appendConsole('> ❌ ' + t('deployReconnect') + ' failed: ' + err.message, 'stderr')
  }
  if (xtermTerminalRef.value.deployRunning) {
    xtermTerminalRef.value.deployRunning = false
    appendConsole('> ' + t('deployRunning') + ' ended', 'info')
  }
}

/** Restore breakpoint glyphs from a list received on reconnect */
function restoreBreakpointsFromList(breakpoints) {
  if (!breakpoints || !Array.isArray(breakpoints)) return
  for (const bp of breakpoints) {
    if (!bp.filePath || bp.lineNumber <= 0) continue
    const key = bp.filePath + ':' + bp.lineNumber
    if (!debugBreakpointLines.value.has(key)) {
      debugBreakpointLines.value.set(key, {
        bpId: bp.id,
        filePath: bp.filePath,
        className: bp.className || '',
        fileName: bp.fileName || '',
        lineNumber: bp.lineNumber,
        enabled: bp.enabled !== false
      })
    }
  }
  // Restore glyphs for the currently active tab
  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (tab) {
    restoreBreakpointGlyphs(tab.path)
  }
}

/** Process debug SSE events */
function processDebugEvent(raw) {
  if (!raw || raw.trim() === '') return
  let eventName = 'message'
  let data = ''
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) eventName = line.substring(6).trim()
    else if (line.startsWith('data:')) data = line.substring(5)
  }
  // Ignore ping events (keepalive from backend)
  if (eventName === 'ping') return
  try {
    if (eventName === 'line') {
      const isErr = /Exception|Error|error|at\s+/.test(data)
      appendConsole(data, isErr ? 'stderr' : 'stdout')
    } else if (eventName === 'error') {
      appendConsole('> ❌ ' + data, 'stderr')
    } else if (eventName === 'breakpointHit') {
      try {
        const info = JSON.parse(data)
        debugState.value = 'suspended'
        stepPending = false
        debugThreadId.value = info.threadId || 0
        appendConsole('> 🐛 ' + t('debugBreakpointHit') + ': ' + (info.fileName || '') + ':' + info.lineNumber, 'info')
        // Highlight breakpoint line
        highlightDebugLine(info.fileName, info.lineNumber, info.className)
        // Fetch stack frames
        fetchStackFrames(info.threadId)
        consoleTab.value = 'debug'
      } catch (e) {
        appendConsole('> 🐛 ' + t('debugBreakpointHit'), 'info')
      }
    } else if (eventName === 'sessionEnd') {
      try {
        const info = JSON.parse(data)
        appendConsole('> ' + t('debugSessionEnd') + ' (exit: ' + (info.exitCode || 0) + ')', 'info')
      } catch {
        appendConsole('> ' + t('debugSessionEnd'), 'info')
      }
      debugState.value = 'disconnected'
      stepPending = false
      debugReconnectAvailable.value = false
      clearDebugHighlights()
    } else if (eventName === 'breakpointsList') {
      try {
        const breakpoints = JSON.parse(data)
        restoreBreakpointsFromList(breakpoints)
        appendConsole('> 🔌 ' + t('debugReconnect') + ': ' + breakpoints.length + ' breakpoints restored', 'info')
      } catch (e) { console.warn('Failed to parse breakpointsList:', e) }
    } else if (eventName === 'breakpointFailed') {
      try {
        const info = JSON.parse(data)
        const key = (info.filePath || info.className) + ':' + info.lineNumber
        removeBreakpointGlyph(key)
        appendConsole('> ⚠ ' + t('breakpointNotSet') + ': ' + (info.fileName || info.className || '') + ':' + info.lineNumber + ' — ' + (info.reason || ''), 'stderr')
      } catch (e) { console.warn('Failed to parse breakpointFailed:', e) }
    } else if (eventName === 'breakpointSet') {
      // Backend confirmed breakpoint was set; glyph already visible
    } else if (eventName === 'debugStarted') {
      appendConsole('> 🐛 ' + t('debugStarted'), 'info')
    }
  } catch (e) {
    console.error('Error processing debug event [' + eventName + ']:', e)
  }
}

/** Fetch stack frames for a thread */
async function fetchStackFrames(threadId) {
  try {
    const { data } = await http.get('/workspace/debug/stackFrames', { params: { threadId } })
    if (data.success && data.frames) {
      debugStackFrames.value = data.frames
      if (data.frames.length > 0) {
        fetchVariables(threadId, 0)
      }
    }
  } catch (e) {
    appendConsole('> ❌ ' + t('debugStackFramesFetchFailed') + ': ' + e.message, 'stderr')
  }
}

/** Fetch variables for a frame */
async function fetchVariables(threadId, frameId) {
  try {
    const { data } = await http.get('/workspace/debug/variables', { params: { threadId, frameId } })
    if (data.success && data.variables) {
      debugVariables.value = data.variables
    }
  } catch (e) {
    appendConsole('> ❌ ' + t('debugVariablesFetchFailed') + ': ' + e.message, 'stderr')
  }
}

/** Select a stack frame and load its variables */
async function selectDebugFrame(threadId, frameId) {
  await fetchVariables(threadId, frameId)
}

/** Highlight a line in the editor when breakpoint is hit */
function highlightDebugLine(fileName, lineNumber, className) {
  if (!editor) return
  clearDebugHighlights()
  const model = editor.getModel()
  if (!model) return
  const uri = model.uri.toString()
  // Only highlight if the file matches the current editor
  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (tab) {
    const tabUri = 'file:///workspace/' + tab.project + '/' + tab.path
    if (!uri.endsWith(fileName) && uri !== tabUri) return
  }
  if (lineNumber > 0) {
    debugDecorations = editor.deltaDecorations(debugDecorations, [
      { range: new monaco.Range(lineNumber, 1, lineNumber, 1), options: { isWholeLine: true, className: 'debug-current-line', glyphMarginClassName: 'debug-current-glyph' } }
    ])
    editor.revealLineInCenter(lineNumber)
  }
}

/** Clear debug line highlights */
function clearDebugHighlights() {
  if (!editor) return
  debugDecorations = editor.deltaDecorations(debugDecorations, [])
}

/** Toggle breakpoint at current line (or specific line from gutter click) */
function toggleBreakpoint(lineNum) {
  if (!editor) return
  const lineNumber = lineNum || (editor.getPosition()?.lineNumber)
  if (!lineNumber) return
  const model = editor.getModel()
  if (!model) return

  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  if (!tab || !tab.project) return

  const source = editor.getValue()
  const className = guessMainClass(tab.path, source)
  const fileName = tab.path.split('/').pop()
  const key = tab.path + ':' + lineNumber

  if (debugBreakpointLines.value.has(key)) {
    // Toggle enabled/disabled on existing breakpoint (IntelliJ-style)
    const entry = debugBreakpointLines.value.get(key)
    toggleBreakpointEnabled({
      key: key,
      className: entry.className,
      fileName: entry.fileName,
      filePath: entry.filePath,
      lineNumber: entry.lineNumber
    })
  } else {
    // Validate line is a valid breakpoint location
    const lineContent = model.getLineContent(lineNumber)
    if (!isValidBreakpointLine(lineContent)) {
      appendConsole('> ⚠ ' + t('invalidBreakpointLine') + ': ' + t('line') + ' ' + lineNumber + ' — ' + lineContent.trim().substring(0, 40), 'stderr')
      return
    }

    // Show breakpoint glyph immediately (optimistic UI)
    const decorations = editor.deltaDecorations([], [
      { range: new monaco.Range(lineNumber, 1, lineNumber, 1), options: { isWholeLine: false, glyphMarginClassName: 'breakpoint-glyph' } }
    ])
    debugBreakpointLines.value.set(key, { decorations, filePath: tab.path, className, fileName, lineNumber, enabled: true })

    // Sync with backend asynchronously
    http.post('/workspace/debug/breakpoint', {
      action: 'set',
      className: className,
      fileName: fileName,
      filePath: tab.path,
      lineNumber: lineNumber
    }).then(({ data }) => {
        if (data.success && data.id) {
        const entry = debugBreakpointLines.value.get(key)
        if (entry) {
          debugBreakpointLines.value.set(key, { ...entry, bpId: data.id })
        }
      } else if (!data.success) {
        // Backend rejected this breakpoint
        removeBreakpointGlyph(key)
        appendConsole('> ⚠ ' + (data.message || t('invalidBreakpointLine')), 'stderr')
      }
    }).catch(() => {})
  }
}

/** Check if a line is a valid breakpoint location (not blank, comment, declaration, etc.) */
function isValidBreakpointLine(text) {
  if (!text) return false
  const t = text.trim()
  if (!t) return false
  // Single-line comments
  if (t.startsWith('//')) return false
  // Package / import declarations
  if (/^(package|import)\s/.test(t)) return false
  // Closing braces/brackets only
  if (/^[}\])]+\s*;?\s*$/.test(t)) return false
  // Annotation-only lines (e.g. @Override, @SuppressWarnings(...))
  if (/^@\w+(\s*\([^)]*\))?\s*$/.test(t)) return false
  // Class / interface / enum / record declarations
  if (/^(public\s+|private\s+|protected\s+)?(static\s+)?(abstract\s+|final\s+|strictfp\s+)?\s*(class|interface|enum|record)\s+\w/.test(t)) return false
  // Method declaration: modifiers + return type + name(params) [throws] {
  // Distinguish from control structures (if/for/while/switch/try) by checking first word
  const firstWord = t.match(/^(\w+)/)
  if (firstWord && !/^(if|for|while|do|switch|try|catch|finally|synchronized|return|throw|new|assert|this|super|break|continue)$/.test(firstWord[1])) {
    // Looks like a declaration — check for method signature pattern ending with {
    if (/^[\w\s<>\[\],.]+\s+\w+\s*\([^)]*\)\s*(throws\s+[\w\s,]+)?\s*\{\s*$/.test(t)) return false
  }
  return true
}

/** Remove breakpoint glyph by key without sending backend request */
function removeBreakpointGlyph(key) {
  const entry = debugBreakpointLines.value.get(key)
  if (!entry) return
  if (entry.decorations && editor) {
    editor.deltaDecorations(entry.decorations, [])
  }
  debugBreakpointLines.value.delete(key)
}

/** Navigate to a breakpoint location (open file, jump to line) */
async function navigateToBreakpoint(bp) {
  if (!bp.filePath || !bp.lineNumber) return
  // Find project from filePath pattern: "src/main/java/..."
  const tab = currentTab.value ? openTabs.value.find(t => t.key === currentTab.value) : null
  const project = tab?.project || activeProject.value
  if (!project) return

  const key = project + ':' + bp.filePath
  // Check if file is already open
  let existing = openTabs.value.find(t => t.key === key)
  if (!existing) {
    try {
      const { data } = await http.get(`/workspace/projects/${project}/file`, { params: { path: bp.filePath } })
        if (data.success) {
        const name = bp.filePath.split('/').pop()
        openTabs.value.push({ key, label: name, project, path: bp.filePath, content: data.content })
        tabContents.value = { ...tabContents.value, [key]: data.content }
        existing = openTabs.value.find(t => t.key === key)
      }
    } catch { return }
  }
  if (existing) {
    switchTab(key)
    nextTick(() => {
      if (editor && bp.lineNumber > 0) {
        editor.setPosition({ lineNumber: bp.lineNumber, column: 1 })
        editor.revealLineInCenter(bp.lineNumber)
      }
    })
  }
  consoleTab.value = 'debug'
}

/** Toggle breakpoint enabled/disabled state */
function toggleBreakpointEnabled(bp) {
  const entry = debugBreakpointLines.value.get(bp.key)
  if (!entry) return
  const newEnabled = !entry.enabled
  debugBreakpointLines.value.set(bp.key, { ...entry, enabled: newEnabled })

  if (newEnabled) {
    // Re-enable on backend
    http.post('/workspace/debug/breakpoint', {
      action: 'set',
      className: bp.className,
      fileName: bp.fileName,
      filePath: bp.filePath,
      lineNumber: bp.lineNumber
    }).then(({ data }) => {
        if (data.success && data.id) {
        const e = debugBreakpointLines.value.get(bp.key)
        if (e) debugBreakpointLines.value.set(bp.key, { ...e, bpId: data.id })
      }
    }).catch(() => {})
    // Restore glyph
    if (editor && entry.decorations) {
      entry.decorations = editor.deltaDecorations(entry.decorations, [
        { range: new monaco.Range(bp.lineNumber, 1, bp.lineNumber, 1), options: { isWholeLine: false, glyphMarginClassName: 'breakpoint-glyph' } }
      ])
    }
  } else {
    // Disable on backend
    if (entry.bpId) {
      http.post('/workspace/debug/breakpoint', { action: 'remove', id: entry.bpId }).catch(() => {})
    }
    // Replace glyph with gray disabled dot (IntelliJ-style)
    if (editor && entry.decorations) {
      const newDecorations = editor.deltaDecorations(entry.decorations, [
        { range: new monaco.Range(bp.lineNumber, 1, bp.lineNumber, 1), options: { isWholeLine: false, glyphMarginClassName: 'breakpoint-glyph-disabled' } }
      ])
      const e = debugBreakpointLines.value.get(bp.key)
      if (e) debugBreakpointLines.value.set(bp.key, { ...e, decorations: newDecorations, bpId: null })
    }
  }
}

/** Remove a breakpoint from the list */
function removeBreakpointById(bp) {
  const entry = debugBreakpointLines.value.get(bp.key)
  if (!entry) return
  if (entry.decorations && editor) {
    editor.deltaDecorations(entry.decorations, [])
  }
  if (entry.bpId) {
    http.post('/workspace/debug/breakpoint', { action: 'remove', id: entry.bpId }).catch(() => {})
  }
  debugBreakpointLines.value.delete(bp.key)
}

/** Restore breakpoint glyphs after switching tabs */
function restoreBreakpointGlyphs(tabPath) {
  if (!editor || !tabPath) return
  // Collect old decoration IDs to remove (from this tab path) so they don't accumulate
  const oldIds = []
  debugBreakpointLines.value.forEach((entry, key) => {
    if (key.startsWith(tabPath + ':') && entry.decorations && entry.decorations.length > 0) {
      oldIds.push(...entry.decorations)
    }
  })
  const newDecorations = []
  const orderedKeys = []
  debugBreakpointLines.value.forEach((entry, key) => {
    if (key.startsWith(tabPath + ':')) {
      const lineNumber = parseInt(key.substring(tabPath.length + 1))
      if (lineNumber > 0) {
        orderedKeys.push(key)
        const className = entry.enabled !== false ? 'breakpoint-glyph' : 'breakpoint-glyph-disabled'
        newDecorations.push({
          range: new monaco.Range(lineNumber, 1, lineNumber, 1),
          options: { isWholeLine: false, glyphMarginClassName: className }
        })
      }
    }
  })
  if (newDecorations.length > 0) {
    // Remove old decorations AND create new ones in one call to prevent accumulation
    const newIds = editor.deltaDecorations(oldIds, newDecorations)
    // Update decoration IDs for restored glyphs, preserving all metadata
    for (let i = 0; i < orderedKeys.length && i < newIds.length; i++) {
      const entry = debugBreakpointLines.value.get(orderedKeys[i])
      if (entry) {
        debugBreakpointLines.value.set(orderedKeys[i], { ...entry, decorations: [newIds[i]] })
      }
    }
  }
}

/** Add breakpoint handling to editor gutter */
function setupDebugEditor() {
  if (!editor) return
  editor.onMouseDown((e) => {
    if (e.target && (e.target.type === monaco.editor.MouseTargetType.GUTTER_GLYPH_MARGIN
        || e.target.type === monaco.editor.MouseTargetType.GUTTER_LINE_NUMBERS)) {
      const lineNumber = e.target.position?.lineNumber
      if (lineNumber) toggleBreakpoint(lineNumber)
    }
  })
}
</script>

<style scoped>
* { box-sizing: border-box; margin: 0; padding: 0; }

/* ==================== Theme Variables ==================== */
.theme-dark {
  --bg-primary: #3c3f41;
  --bg-secondary: #2b2b2b;
  --bg-tertiary: #45494a;
  --bg-hover: #4e5254;
  --bg-active: #214283;
  --bg-input: #2b2b2b;
  --text-primary: #bbb;
  --text-secondary: #999;
  --text-muted: #666;
  --text-bright: #fff;
  --text-brand: #e8a427;
  --border-color: #2b2b2b;
  --border-input: #555;
  --border-focus: #6897bb;
  --run-color: #59a869;
  --run-hover: #365a3e;
  --stop-color: #c75450;
  --error-color: #c75450;
  --badge-bg: #c75450;
  --tag-bg: #2b2b2b;
  --tag-color: #888;
  --scrollbar-track: #2b2b2b;
  --scrollbar-thumb: #555;
  --scrollbar-hover: #666;
  --shadow-color: rgba(0,0,0,0.5);
  --tab-active-border: #6897bb;
  --console-stdout: #a9b7c6;
  --console-info: #6a8759;
  --accent-color: #6897bb;
  --accent-bg: rgba(104,151,187,0.2);
  --accent-color-darken: #4a7a9e;
}

.theme-light {
  --bg-primary: #f3f3f3;
  --bg-secondary: #ffffff;
  --bg-tertiary: #e8e8e8;
  --bg-hover: #d4d4d4;
  --bg-active: #c8ddf9;
  --bg-input: #ffffff;
  --text-primary: #333;
  --text-secondary: #666;
  --text-muted: #999;
  --text-bright: #000;
  --text-brand: #b8860b;
  --border-color: #d0d0d0;
  --border-input: #c0c0c0;
  --border-focus: #0078d4;
  --run-color: #388e3c;
  --run-hover: #c8e6c9;
  --stop-color: #d32f2f;
  --error-color: #d32f2f;
  --badge-bg: #d32f2f;
  --tag-bg: #e0e0e0;
  --tag-color: #666;
  --scrollbar-track: #f0f0f0;
  --scrollbar-thumb: #c0c0c0;
  --scrollbar-hover: #a0a0a0;
  --shadow-color: rgba(0,0,0,0.15);
  --tab-active-border: #0078d4;
  --console-stdout: #333;
  --console-info: #2e7d32;
  --accent-color: #0078d4;
  --accent-bg: rgba(0,120,212,0.15);
  --accent-color-darken: #005a9e;
}

.eclipse-ide { height: 100vh; width: 100vw; display: flex; flex-direction: column; background: var(--bg-primary); color: var(--text-primary); font-family: 'Segoe UI', Tahoma, sans-serif; font-size: 13px; overflow: hidden; }

/* Header Bar (merged menu + toolbar) */
.header-bar { display: flex; align-items: center; gap: 4px; height: 34px; background: var(--bg-primary); border-bottom: 1px solid var(--border-color); padding: 0 8px; flex-shrink: 0; }
.header-brand { color: var(--text-brand); font-weight: 600; font-size: 13px; margin-right: 4px; white-space: nowrap; }
.header-spacer { flex: 1; }
.menu-item { padding: 4px 10px; cursor: pointer; color: var(--text-primary); font-size: 12px; border-radius: 2px; }
.menu-item:hover { background: var(--bg-hover); color: var(--text-bright); }
.theme-toggle { font-size: 14px; }

/* Tool buttons */
.tool-btn { width: 26px; height: 26px; border: none; background: transparent; color: var(--text-primary); font-size: 14px; cursor: pointer; border-radius: 3px; display: flex; align-items: center; justify-content: center; }
.tool-btn:hover { background: var(--bg-hover); }
.tool-btn:disabled { opacity: 0.4; cursor: default; }
.run-btn { color: var(--run-color); font-size: 16px; }
.run-btn:hover { background: var(--run-hover); }
.stop-btn { color: var(--stop-color); font-size: 10px; }
.rebuild-btn { color: var(--border-focus); font-size: 13px; }
.deploy-btn { color: var(--run-color); font-size: 16px; }
.deploy-btn:hover { background: var(--run-hover); }
.deploy-spinner { display: inline-block; animation: spin 1s linear infinite; }
.tool-sep { width: 1px; height: 18px; background: var(--border-input); margin: 0 4px; }
.run-group { display: flex; align-items: center; gap: 0; }
.run-config-btn { width: 16px; font-size: 10px; color: var(--text-secondary); }
.run-config-btn:hover { color: var(--text-bright); }
.build-btn { font-size: 13px; }
.run-config-label { font-size: 11px; color: var(--text-secondary); cursor: pointer; padding: 2px 8px; border-radius: 3px; max-width: 150px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.run-config-label:hover { background: var(--bg-hover); color: var(--text-bright); }
.spinner { width: 12px; height: 12px; border: 2px solid var(--border-input); border-top-color: var(--run-color); border-radius: 50%; animation: spin 0.6s linear infinite; display: inline-block; }
@keyframes spin { to { transform: rotate(360deg); } }

/* Run Configuration Panel */
.run-config-panel { position: absolute; top: 34px; left: 100px; z-index: 100; width: 380px; background: var(--bg-secondary, #2b2b2b); border: 1px solid var(--border-color); border-radius: 6px; box-shadow: 0 4px 16px var(--shadow-color); }
.run-config-header { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; border-bottom: 1px solid var(--border-color); font-size: 12px; font-weight: 600; color: var(--text-primary); }
.run-config-close { cursor: pointer; color: var(--text-secondary); font-size: 14px; }
.run-config-close:hover { color: var(--text-bright); }
.run-config-body { padding: 8px 12px; }
.run-config-row { margin-bottom: 8px; }
.run-config-row label { display: block; font-size: 11px; color: var(--text-secondary); margin-bottom: 3px; }
.run-config-row input { width: 100%; box-sizing: border-box; background: var(--bg-input); border: 1px solid var(--border-input); color: var(--text-primary); font-size: 12px; padding: 4px 8px; border-radius: 3px; outline: none; }
.run-config-row input:focus { border-color: var(--border-focus); }
.run-config-main-class { display: flex; gap: 4px; }
.run-config-main-class input { flex: 1; }
.run-config-main-class button { width: 28px; height: 26px; border: 1px solid var(--border-input); background: var(--bg-tertiary); color: var(--text-primary); cursor: pointer; border-radius: 3px; font-size: 12px; }
.run-config-main-class button:hover { background: var(--bg-hover); }
.detected-classes { margin-top: 4px; border: 1px solid var(--border-input); border-radius: 3px; max-height: 120px; overflow-y: auto; }
.detected-class-item { padding: 4px 8px; font-size: 11px; cursor: pointer; color: var(--text-primary); }
.detected-class-item:hover { background: var(--bg-hover); }
.run-config-sep { height: 1px; background: var(--border-input); margin: 8px 0; }
.run-config-section-title { font-size: 11px; font-weight: 600; color: var(--border-focus); margin-bottom: 6px; text-transform: uppercase; letter-spacing: 0.5px; }
.run-config-build-btns { display: flex; flex-wrap: wrap; gap: 4px; }
.run-config-build-btn { padding: 3px 10px; border: 1px solid var(--border-input); background: var(--bg-tertiary); color: var(--text-primary); font-size: 11px; border-radius: 3px; cursor: pointer; }
.run-config-build-btn:hover { background: var(--bg-hover); color: var(--text-bright); }

/* Workbench */
.workbench { display: flex; flex: 1; min-height: 0; overflow: hidden; }

/* Sidebar */
.sidebar { background: var(--bg-primary); border-right: 1px solid var(--border-color); display: flex; flex-direction: column; flex-shrink: 0; overflow: hidden; }
.view-header { height: 24px; display: flex; align-items: center; justify-content: space-between; padding: 0 8px; background: var(--bg-tertiary); border-bottom: 1px solid var(--border-color); font-size: 11px; font-weight: 600; color: var(--text-primary); }
.view-actions { display: flex; gap: 2px; }
.view-action { width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; cursor: pointer; border-radius: 3px; font-size: 12px; color: var(--text-primary); }
.view-action:hover { background: var(--bg-hover); color: var(--text-bright); }
.package-tree { flex: 1; overflow-y: auto; padding: 4px 0; }
.tree-empty { color: var(--text-muted); font-style: italic; padding: 16px 12px; text-align: center; font-size: 11px; }
.tree-node { display: flex; align-items: center; gap: 4px; padding: 2px 8px; cursor: pointer; font-size: 12px; color: var(--text-primary); white-space: nowrap; user-select: none; }
.tree-node:hover { background: var(--bg-hover); }
.tree-node.active { background: var(--bg-active); color: var(--text-bright); }
.tree-node .icon { font-size: 14px; width: 16px; text-align: center; flex-shrink: 0; }
.tree-node .tag { font-size: 9px; color: var(--tag-color); background: var(--tag-bg); padding: 0 4px; border-radius: 3px; margin-left: 4px; flex-shrink: 0; }
.tree-node .tag.size { color: var(--console-info); }
.tree-node .tag.scope { color: var(--border-focus); }
.project-refresh-btn { font-size: 10px; margin-left: auto; opacity: 0; padding: 2px 4px; cursor: pointer; flex-shrink: 0; }
.tree-node:hover .project-refresh-btn { opacity: 0.6; }
.project-refresh-btn:hover { opacity: 1 !important; }
.dep-label { font-size: 11px; color: var(--text-secondary); }

/* Resize */
.resize-handle-v { width: 4px; cursor: col-resize; background: var(--border-color); flex-shrink: 0; }
.resize-handle-v:hover { background: var(--border-focus); }
.resize-handle-h { height: 4px; cursor: row-resize; background: var(--border-color); flex-shrink: 0; }
.resize-handle-h:hover { background: var(--border-focus); }

/* Center */
.center-area { flex: 1; display: flex; flex-direction: column; min-width: 0; overflow: hidden; }

/* Editor */
.editor-area { flex: 1; display: flex; flex-direction: column; min-height: 100px; overflow: hidden; }
.editor-tabs-bar { display: flex; align-items: center; height: 28px; background: var(--bg-primary); border-bottom: 1px solid var(--border-color); flex-shrink: 0; overflow-x: auto; }
.editor-tab { display: flex; align-items: center; gap: 4px; padding: 0 12px; height: 100%; font-size: 12px; color: var(--text-secondary); cursor: pointer; border-right: 1px solid var(--border-color); background: var(--bg-primary); white-space: nowrap; flex-shrink: 0; }
.editor-tab.active { background: var(--bg-secondary); color: var(--text-bright); border-bottom: 2px solid var(--tab-active-border); margin-bottom: -1px; }
.tab-icon { font-size: 13px; }
.tab-close { margin-left: 6px; font-size: 14px; color: var(--text-muted); cursor: pointer; width: 16px; height: 16px; display: flex; align-items: center; justify-content: center; border-radius: 2px; }
.tab-close:hover { background: var(--border-input); color: var(--text-bright); }
.tab-dirty { color: var(--text-brand); font-size: 10px; margin-left: 2px; }
.editor-container { flex: 1; min-height: 0; }

/* Console */
.console-area { display: flex; flex-direction: column; background: var(--bg-secondary); border-top: 1px solid var(--border-color); flex-shrink: 0; overflow: hidden; }
.console-tabs-bar { display: flex; align-items: center; height: 26px; background: var(--bg-primary); border-bottom: 1px solid var(--border-color); flex-shrink: 0; }
.console-tab { display: flex; align-items: center; gap: 4px; padding: 0 10px; height: 100%; font-size: 11px; color: var(--text-secondary); cursor: pointer; border-right: 1px solid var(--border-color); }
.console-tab.active { background: var(--bg-secondary); color: var(--text-bright); }
.console-tab:hover { color: var(--text-primary); }
.badge { background: var(--badge-bg); color: #fff; font-size: 10px; padding: 0 5px; border-radius: 8px; margin-left: 4px; }
.console-spacer { flex: 1; }
.console-btn { padding: 2px 6px; cursor: pointer; font-size: 12px; color: var(--text-secondary); border-radius: 2px; }
.console-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.console-body { flex: 1; overflow: hidden; padding: 0; min-height: 0; }
.console-content { padding: 6px 10px; height: 100%; overflow-y: auto; }
.console-line { font-family: 'Consolas', 'JetBrains Mono', 'Courier New', monospace; font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
.console-line.stdout { color: var(--console-stdout); }
.console-line.stderr { color: var(--error-color); }
.console-line.info { color: var(--console-info); }
.console-empty { color: var(--text-muted); font-style: italic; padding: 20px; text-align: center; font-size: 12px; }

/* Terminal */
.terminal-content { display: flex; flex-direction: column; height: 100%; overflow: hidden; }
.terminal-lines { flex: 1; overflow-y: auto; padding: 4px 8px; font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }
.terminal-input-bar { display: flex; align-items: center; padding: 4px 8px; border-top: 1px solid var(--border-color); background: var(--bg-primary); gap: 6px; }
.terminal-prompt { color: var(--console-info); font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; white-space: nowrap; flex-shrink: 0; }
.terminal-input { flex: 1; background: transparent; border: none; outline: none; color: var(--text-primary); font-family: 'Consolas', 'Courier New', monospace; font-size: 12px; }
.terminal-input::placeholder { color: var(--text-muted); }
.terminal-input:disabled { opacity: 0.5; }

/* Problems */
.problems-content { padding: 0; }
.problems-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.problems-table th { text-align: left; padding: 4px 8px; background: var(--bg-primary); border-bottom: 1px solid var(--border-color); color: var(--text-secondary); font-weight: normal; position: sticky; top: 0; }
.problems-table td { padding: 3px 8px; border-bottom: 1px solid var(--border-color); color: var(--text-primary); }
.problems-table tr:hover td { background: var(--bg-hover); }
.problems-table .problem-row { cursor: pointer; }

/* Context Menu */
.context-menu { position: fixed; background: var(--bg-primary); border: 1px solid var(--border-input); border-radius: 4px; padding: 4px 0; min-width: 160px; box-shadow: 0 4px 12px var(--shadow-color); z-index: 1000; }
.context-menu-item { padding: 6px 16px; font-size: 12px; color: var(--text-primary); cursor: pointer; display: flex; align-items: center; gap: 8px; }
.context-menu-item:hover { background: var(--bg-active); color: var(--text-bright); }
.context-menu-item.danger { color: var(--error-color); }
.context-menu-item.danger:hover { background: #5a2020; color: #ff8888; }
.context-menu-sep { height: 1px; background: var(--border-input); margin: 4px 8px; }

/* Dialog */
.dialog-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: rgba(0,0,0,0.5); display: flex; align-items: center; justify-content: center; z-index: 2000; }

/* Login */
.login-overlay { position: fixed; top: 0; left: 0; right: 0; bottom: 0; background: var(--bg-primary); display: flex; align-items: center; justify-content: center; z-index: 3000; }
.login-box { background: var(--bg-secondary); border: 1px solid var(--border-color); border-radius: 8px; padding: 32px; width: 320px; box-shadow: 0 8px 32px var(--shadow-color); position: relative; }
.login-lang { position: absolute; top: 8px; right: 12px; }
.login-title { font-size: 18px; font-weight: 600; color: var(--text-primary); text-align: center; margin-bottom: 4px; }
.login-hint { font-size: 12px; color: var(--text-secondary); text-align: center; margin-bottom: 20px; }
.login-input { width: 100%; padding: 8px 12px; margin-bottom: 12px; background: var(--bg-primary); border: 1px solid var(--border-input); border-radius: 4px; color: var(--text-primary); font-size: 13px; outline: none; box-sizing: border-box; }
.login-input:focus { border-color: var(--border-focus); }
.login-error { color: var(--error-color); font-size: 12px; margin-bottom: 12px; text-align: center; }
.login-btn { width: 100%; padding: 8px; background: var(--bg-active); color: var(--text-bright); border: none; border-radius: 4px; font-size: 13px; cursor: pointer; }
.login-btn:hover { filter: brightness(1.15); }
.login-btn:disabled { opacity: 0.6; cursor: not-allowed; filter: none; }
.user-info { font-size: 11px; color: var(--text-secondary); cursor: default; }
.logout-btn { cursor: pointer; display: flex; align-items: center; color: var(--text-secondary); }
.logout-btn:hover { color: var(--text-bright); }
.lang-select { background: transparent; border: 1px solid var(--border-input); color: var(--text-secondary); font-size: 11px; padding: 1px 2px; border-radius: 3px; cursor: pointer; outline: none; margin: 0 4px; }
.lang-select option { background: var(--bg-primary); color: var(--text-primary); }
.settings-toggle { display: flex; align-items: center; gap: 8px; font-size: 12px; color: var(--text-primary); cursor: pointer; }
.settings-toggle input[type="checkbox"] { cursor: pointer; }
.dialog-box { background: var(--bg-primary); border: 1px solid var(--border-input); border-radius: 6px; padding: 20px; min-width: 340px; box-shadow: 0 8px 24px var(--shadow-color); }
.dialog-title { font-size: 14px; color: var(--text-primary); margin-bottom: 12px; font-weight: 600; }
.dialog-input { width: 100%; height: 30px; background: var(--bg-input); border: 1px solid var(--border-input); color: var(--text-primary); font-size: 13px; padding: 0 8px; border-radius: 3px; margin-bottom: 16px; }
.dialog-input:focus { border-color: var(--border-focus); outline: none; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
.dialog-btn { padding: 6px 16px; border: 1px solid var(--border-input); background: var(--bg-tertiary); color: var(--text-primary); font-size: 12px; border-radius: 3px; cursor: pointer; }
.dialog-btn:hover { background: var(--bg-hover); color: var(--text-bright); }
.dialog-btn.primary { background: var(--bg-active); border-color: var(--bg-active); color: var(--text-bright); }
.dialog-btn.primary:hover { filter: brightness(1.15); }
.dialog-btn.primary:disabled { opacity: 0.5; cursor: not-allowed; filter: none; }

/* New Project Dialog */
.dialog-field { margin-bottom: 12px; }
.dialog-label { display: block; font-size: 12px; color: var(--text-secondary); margin-bottom: 4px; font-weight: 500; }

/* Branch Dialog */
.branch-dialog { min-width: 380px; max-width: 440px; }
.branch-current { font-size: 12px; color: var(--text-secondary); margin-bottom: 8px; }
.branch-current b { color: var(--text-primary); }
.branch-list { max-height: 240px; overflow-y: auto; border: 1px solid var(--border-input); border-radius: 3px; margin: 8px 0; }
.branch-item { display: flex; align-items: center; padding: 6px 10px; cursor: pointer; font-size: 12px; color: var(--text-primary); gap: 6px; }
.branch-item:hover { background: var(--bg-hover); }
.branch-item.active { background: var(--bg-active); color: var(--text-bright); }
.branch-icon { flex-shrink: 0; }
.branch-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.branch-current-tag { font-size: 10px; background: var(--bg-active); color: var(--text-bright); padding: 1px 6px; border-radius: 3px; flex-shrink: 0; }
.branch-empty { font-size: 12px; color: var(--text-secondary); text-align: center; padding: 16px 0; }

/* Decompile Class Browser Dialog */
.decompile-dialog { min-width: 420px; max-width: 500px; max-height: 500px; display: flex; flex-direction: column; }
.decompile-dialog .dialog-input { margin-bottom: 8px; }
.decompile-class-list { max-height: 320px; overflow-y: auto; border: 1px solid var(--border-input); border-radius: 3px; margin: 4px 0 12px 0; flex: 1; }
.decompile-class-item { display: flex; align-items: center; padding: 5px 10px; cursor: pointer; font-size: 12px; color: var(--text-primary); gap: 6px; }
.decompile-class-item:hover { background: var(--bg-hover); color: var(--text-bright); }
.decompile-class-item.decompiling { opacity: 0.6; cursor: wait; }
.decompile-class-icon { flex-shrink: 0; font-size: 13px; }
.decompile-class-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.decompile-empty { font-size: 12px; color: var(--text-secondary); text-align: center; padding: 24px 0; }
.decompile-loading { font-size: 12px; color: var(--text-secondary); text-align: center; padding: 24px 0; }

/* Settings Panel */
.settings-panel { background: var(--bg-primary); border: 1px solid var(--border-input); border-radius: 8px; width: 560px; max-height: 80vh; display: flex; flex-direction: column; box-shadow: 0 8px 32px var(--shadow-color); }
.settings-header { display: flex; align-items: center; justify-content: space-between; padding: 12px 16px; border-bottom: 1px solid var(--border-color); }
.settings-title { font-size: 14px; font-weight: 600; color: var(--text-bright); }
.settings-close { font-size: 18px; cursor: pointer; color: var(--text-secondary); width: 24px; height: 24px; display: flex; align-items: center; justify-content: center; border-radius: 3px; }
.settings-close:hover { background: var(--bg-hover); color: var(--text-bright); }
.settings-body { flex: 1; overflow-y: auto; padding: 16px; }
.settings-group { margin-bottom: 20px; }
.settings-group-title { font-size: 12px; font-weight: 600; color: var(--border-focus); margin-bottom: 10px; text-transform: uppercase; letter-spacing: 0.5px; }
.settings-row { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.settings-label { font-size: 12px; color: var(--text-primary); }
.settings-select { height: 28px; background: var(--bg-input); border: 1px solid var(--border-input); color: var(--text-primary); font-size: 12px; padding: 0 8px; border-radius: 3px; }
.settings-select:focus { border-color: var(--border-focus); outline: none; }
.settings-input { height: 28px; background: var(--bg-input); border: 1px solid var(--border-input); color: var(--text-primary); font-size: 12px; padding: 0 8px; border-radius: 3px; width: 100%; }
.settings-input:focus { border-color: var(--border-focus); outline: none; }
.settings-hint { font-size: 11px; color: var(--error-color); }
.settings-mode-hint { font-size: 11px; color: var(--text-secondary); font-style: italic; padding: 4px 0; }
.settings-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--border-color); }

/* Right Tool Bar (IntelliJ-style vertical tabs) */
.right-tool-bar { display: flex; flex-direction: column; width: 24px; background: var(--bg-primary); border-left: 1px solid var(--border-color); flex-shrink: 0; padding-top: 4px; gap: 2px; }
.right-tool-tab { writing-mode: vertical-rl; text-orientation: mixed; padding: 8px 4px; font-size: 11px; color: var(--text-secondary); cursor: pointer; border-radius: 0; user-select: none; display: flex; align-items: center; justify-content: center; }
.right-tool-tab:hover { background: var(--bg-hover); color: var(--text-primary); }
.right-tool-tab.active { background: var(--bg-active); color: var(--text-bright); border-left: 2px solid var(--border-focus); }
.right-tool-tab-text { white-space: nowrap; letter-spacing: 1px; font-weight: 500; }

/* Right Panel (Maven/Gradle Tool Window) */
.right-panel { display: flex; flex-shrink: 0; border-left: 1px solid var(--border-color); background: var(--bg-primary); overflow: hidden; position: relative; }
.right-panel-inner { display: flex; flex-direction: column; flex: 1; min-width: 0; overflow: hidden; }
.right-panel .right-resize { position: absolute; left: 0; top: 0; bottom: 0; width: 4px; cursor: col-resize; z-index: 1; }
.right-panel-header { display: flex; align-items: center; justify-content: space-between; height: 28px; padding: 0 8px; background: var(--bg-tertiary); border-bottom: 1px solid var(--border-color); flex-shrink: 0; }
.right-panel-title { font-size: 12px; font-weight: 600; color: var(--text-primary); }
.right-panel-actions { display: flex; gap: 2px; }
.right-panel-toolbar { display: flex; align-items: center; gap: 2px; padding: 3px 6px; border-bottom: 1px solid var(--border-color); flex-shrink: 0; }
.rp-tool-btn { width: 22px; height: 22px; display: flex; align-items: center; justify-content: center; cursor: pointer; border-radius: 3px; font-size: 12px; color: var(--text-secondary); }
.rp-tool-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.right-panel-body { flex: 1; overflow-y: auto; padding: 2px 0; }

/* Right Panel Tree */
.rp-tree-node { display: flex; align-items: center; gap: 3px; padding: 3px 8px; cursor: pointer; font-size: 12px; color: var(--text-primary); white-space: nowrap; user-select: none; }
.rp-tree-node:hover { background: var(--bg-hover); }
.rp-tree-node.root { font-weight: 600; }
.rp-tree-node.rp-leaf { cursor: default; }
.rp-tree-node.rp-leaf:hover { background: var(--bg-hover); cursor: pointer; }
.rp-icon { width: 12px; font-size: 8px; color: var(--text-muted); flex-shrink: 0; text-align: center; }
.rp-icon-img { font-size: 13px; width: 16px; text-align: center; flex-shrink: 0; }
.rp-label { overflow: hidden; text-overflow: ellipsis; }
.rp-plugin-label { color: var(--text-secondary); font-size: 11px; }
.rp-dep-label { font-size: 11px; color: var(--text-secondary); }
.rp-tag { font-size: 9px; color: var(--tag-color); background: var(--tag-bg); padding: 0 4px; border-radius: 3px; margin-left: 4px; flex-shrink: 0; }
.rp-tree-empty { font-size: 11px; color: var(--text-muted); font-style: italic; padding: 4px 8px; }

/* Status Bar */
.statusbar { display: flex; align-items: center; height: 22px; background: var(--bg-primary); border-top: 1px solid var(--border-color); padding: 0 10px; font-size: 11px; color: var(--text-secondary); flex-shrink: 0; }
.status-item { padding: 0 6px; }
.status-sep { color: var(--border-input); }
.status-spacer { flex: 1; }

/* Scrollbar */
.package-tree::-webkit-scrollbar, .console-body::-webkit-scrollbar, .settings-body::-webkit-scrollbar { width: 8px; }
.package-tree::-webkit-scrollbar-track, .console-body::-webkit-scrollbar-track, .settings-body::-webkit-scrollbar-track { background: var(--scrollbar-track); }
.package-tree::-webkit-scrollbar-thumb, .console-body::-webkit-scrollbar-thumb, .settings-body::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 4px; }
.package-tree::-webkit-scrollbar-thumb:hover, .console-body::-webkit-scrollbar-thumb:hover, .settings-body::-webkit-scrollbar-thumb:hover { background: var(--scrollbar-hover); }

/* ==================== Search Panel (VS Code style) ==================== */
.search-panel-wrapper {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  z-index: 1000; display: flex; justify-content: center;
  pointer-events: none;
}
.search-backdrop {
  position: fixed; top: 0; left: 0; right: 0; bottom: 0;
  z-index: -1; pointer-events: auto;
}
.search-panel {
  position: absolute; top: 36px;
  width: 620px; max-width: 90vw; max-height: 500px;
  background: var(--bg-primary); border: 1px solid var(--border-color);
  border-radius: 6px; box-shadow: 0 6px 24px rgba(0,0,0,0.5);
  display: flex; flex-direction: column; overflow: hidden;
  pointer-events: auto;
}
.search-input-row {
  display: flex; align-items: center; padding: 8px 10px; gap: 6px;
  border-bottom: 1px solid var(--border-color);
}
.search-icon { font-size: 15px; flex-shrink: 0; color: var(--text-secondary); }
.search-input {
  flex: 1; background: var(--bg-input); color: var(--text-primary);
  border: 1px solid var(--border-input); border-radius: 4px;
  padding: 5px 10px; font-size: 14px; outline: none;
  font-family: inherit;
}
.search-input:focus { border-color: var(--border-focus); }
.search-project-tag {
  font-size: 11px; color: var(--text-secondary); background: var(--bg-tertiary);
  padding: 2px 8px; border-radius: 3px; white-space: nowrap; flex-shrink: 0;
}
.search-close-btn {
  cursor: pointer; color: var(--text-secondary); font-size: 14px; padding: 2px 4px;
  border-radius: 3px; flex-shrink: 0;
}
.search-close-btn:hover { background: var(--bg-hover); color: var(--text-primary); }
.search-filter-row {
  display: flex; align-items: center; padding: 4px 10px; gap: 8px;
  border-bottom: 1px solid var(--border-color); flex-wrap: wrap;
}
.search-type-tabs { display: flex; gap: 2px; flex-shrink: 0; }
.search-type-tab {
  padding: 3px 8px; font-size: 11px; cursor: pointer;
  border-radius: 3px; color: var(--text-secondary);
  border: 1px solid transparent; user-select: none;
}
.search-type-tab:hover { background: var(--bg-hover); }
.search-type-tab.active {
  background: var(--bg-active); color: var(--text-bright);
  border-color: var(--border-focus);
}
.search-ext-bar {
  display: flex; align-items: center; gap: 3px; flex: 1; min-width: 0;
}
.search-ext-chip {
  padding: 2px 7px; font-size: 10px; cursor: pointer;
  border-radius: 3px; color: var(--text-secondary);
  border: 1px solid var(--border-input); user-select: none; white-space: nowrap;
}
.search-ext-chip:hover { background: var(--bg-hover); }
.search-ext-chip.active {
  background: var(--bg-active); color: var(--text-bright); border-color: var(--border-focus);
}
.search-ext-input {
  flex: 1; min-width: 80px; max-width: 160px;
  background: var(--bg-input); color: var(--text-primary);
  border: 1px solid var(--border-input); border-radius: 3px;
  padding: 2px 6px; font-size: 10px; outline: none;
}
.search-ext-input:focus { border-color: var(--border-focus); }
.search-results {
  flex: 1; overflow-y: auto; padding: 4px 0;
  max-height: 380px;
}
.search-empty {
  padding: 20px; text-align: center; color: var(--text-muted); font-size: 13px;
}
.search-result-item {
  display: flex; align-items: center; padding: 5px 12px; gap: 8px;
  cursor: pointer; font-size: 13px;
}
.search-result-item:hover, .search-result-item.selected {
  background: var(--bg-hover);
}
.search-result-icon { font-size: 14px; flex-shrink: 0; width: 20px; text-align: center; }
.search-result-info {
  flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 1px;
}
.search-result-name {
  color: var(--text-primary); font-weight: 500; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.search-result-detail {
  color: var(--text-muted); font-size: 11px; white-space: nowrap;
  overflow: hidden; text-overflow: ellipsis;
}
.search-result-path {
  color: var(--text-muted); font-size: 11px; flex-shrink: 0;
  max-width: 200px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
  direction: rtl; text-align: left;
}
.search-result-badge {
  font-size: 10px; padding: 1px 5px; border-radius: 3px; flex-shrink: 0;
  color: #fff;
}
.search-result-badge.file { background: #3a7; }
.search-result-badge.symbol { background: #47a; }
.search-results::-webkit-scrollbar { width: 6px; }
.search-results::-webkit-scrollbar-track { background: transparent; }
.search-results::-webkit-scrollbar-thumb { background: var(--scrollbar-thumb); border-radius: 3px; }

/* ==================== Git Tab ==================== */
.git-content { padding: 0; overflow-y: auto; height: 100%; }
.git-status-bar {
  display: flex; align-items: center; gap: 8px; padding: 4px 12px;
  background: var(--bg-tertiary); border-bottom: 1px solid var(--border-color); font-size: 12px;
}
.git-status-label { color: var(--text-secondary); }
.git-status-count { padding: 1px 6px; border-radius: 3px; font-size: 11px; color: #fff; }
.git-status-count.modified { background: #c08020; }
.git-status-count.added { background: #3a7; }
.git-status-count.untracked { background: #666; }
.git-status-count.removed { background: #c44; }
.git-status-count.conflict { background: #c44; }
.git-log-header {
  display: flex; justify-content: space-between; align-items: center;
  padding: 4px 12px; font-size: 12px; color: var(--text-dim);
  border-bottom: 1px solid var(--border);
}
.git-log-more { cursor: pointer; color: #4a9eff; }
.git-log-more:hover { text-decoration: underline; }
.git-log-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.git-log-table th {
  text-align: left; padding: 3px 8px; color: var(--text-dim);
  border-bottom: 1px solid var(--border); font-weight: normal; position: sticky; top: 0;
  background: var(--bg-sidebar);
}
.git-log-table td { padding: 3px 8px; border-bottom: 1px solid var(--border); color: var(--text); }
.git-log-table tr:hover td { background: var(--hover); }
.git-log-wrapper { max-height: 300px; overflow-y: auto; }
#git-log-tbody { display: block; }
.git-log-loading { text-align: center; color: var(--text-dim); padding: 8px !important; }
.git-commit-id { font-family: monospace; color: #4a9eff; cursor: pointer; }
.git-commit-msg { max-width: 300px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.git-action-btn { cursor: pointer; margin-right: 4px; font-size: 13px; }
.git-action-btn:hover { opacity: 0.7; }
.vcs-branch { font-size: 11px; color: #4a9eff; cursor: default; padding: 0 6px; }
.console-btn { cursor: pointer; padding: 0 4px; font-size: 13px; user-select: none; }
.console-btn:hover { opacity: 0.7; }

/* ==================== Debug Styles ==================== */
.debug-status-badge { font-size: 11px; padding: 0 4px; }
.debug-status-badge.suspended { color: #f1c40f; }
.debug-status-badge.running { color: #2ecc71; }
.debug-panel { height: 100%; overflow-y: auto; display: flex; flex-direction: column; font-size: 12px; }
.debug-section { border-bottom: 1px solid var(--border-color, #333); }
.debug-breakpoint-item { padding: 3px 8px; cursor: pointer; display: flex; align-items: center; gap: 6px; font-size: 12px; }
.debug-breakpoint-item:hover { background: var(--bg-hover, #4e5254); }
.debug-bp-checkbox { cursor: pointer; flex-shrink: 0; }
.debug-bp-location { font-family: monospace; font-size: 11px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; color: var(--text-primary, #bbb); }
.debug-bp-location.debug-bp-disabled { color: var(--text-muted, #666); text-decoration: line-through; }
.debug-bp-line { color: var(--text-brand, #e8a427); font-weight: bold; }
.debug-bp-remove { background: none; border: none; color: var(--text-muted, #666); cursor: pointer; font-size: 11px; padding: 0 2px; flex-shrink: 0; }
.debug-bp-remove:hover { color: #e74c3c; }
.debug-section-title { font-weight: bold; padding: 4px 8px; background: var(--bg-tertiary, #45494a); color: var(--text-secondary, #999); text-transform: uppercase; font-size: 11px; position: sticky; top: 0; }
.debug-empty { padding: 12px 8px; color: var(--text-muted, #666); font-style: italic; }
.debug-frame-item { padding: 4px 8px; cursor: pointer; display: flex; justify-content: space-between; gap: 8px; }
.debug-frame-item:hover { background: var(--bg-hover, #4e5254); }
.debug-frame-item.active { background: var(--bg-active, #214283); color: var(--text-bright, #fff); }
.debug-frame-name { font-family: monospace; font-size: 12px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.debug-frame-location { color: var(--text-secondary, #999); font-size: 11px; white-space: nowrap; }
.debug-variable-item { padding: 2px 8px 2px 12px; display: flex; gap: 4px; align-items: baseline; font-family: monospace; font-size: 12px; }
.debug-variable-item:hover { background: var(--bg-hover, #4e5254); }
.debug-child-item { padding-left: 24px; }
.debug-var-name { color: #4a9eff; }
.debug-var-sep { color: var(--text-secondary, #999); }
.debug-var-value { color: var(--text-primary, #bbb); word-break: break-all; }
.debug-var-value.debug-var-null { color: var(--text-muted, #666); font-style: italic; }
.debug-var-type { color: var(--text-muted, #666); font-size: 11px; }
.debug-var-children { border-left: 1px solid var(--border-color, #333); margin-left: 4px; margin-top: 2px; }
</style>

<!-- Non-scoped styles for Monaco Editor dynamic elements (glyph margin decorations) -->
<style>
.breakpoint-glyph { background: #e74c3c; border-radius: 50%; width: 10px !important; height: 10px !important; margin-left: 5px; margin-top: 3px; }
.breakpoint-glyph-disabled { background: #888; border-radius: 50%; width: 10px !important; height: 10px !important; margin-left: 5px; margin-top: 3px; }
.debug-current-line { background: rgba(255, 255, 0, 0.1) !important; }
.debug-current-glyph { background: #f1c40f; border-radius: 0; width: 4px !important; height: 100% !important; }
</style>
