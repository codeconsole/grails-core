<%@ page import="grails.util.Environment" %>
<%@ page import="org.springframework.boot.SpringBootVersion" %>
<%@ page import="org.springframework.core.SpringVersion" %>
<%@ page import="org.springframework.util.ClassUtils" %>
<!doctype html>
<html lang="en">
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title><g:layoutTitle default="Grails"/></title>
    <asset:link rel="icon" href="favicon.ico" type="image/x-ico"/>
    <asset:stylesheet src="application.css"/>
    <asset:javascript src="theme.js"/>
    <g:layoutHead/>
</head>

<body>

<nav class="navbar navbar-expand-lg bg-body border-bottom shadow-sm">
    <div class="container-lg">
        <a class="navbar-brand d-flex align-items-center" href="${request.contextPath}/">
            <asset:image class="w-75" src="grails.svg" alt="Grails Logo"/>
        </a>
        <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#mainNavbar"
                aria-controls="mainNavbar" aria-expanded="false" aria-label="${message(code: 'layout.status')}">
            <span class="navbar-toggler-icon"></span>
        </button>
        <div class="collapse navbar-collapse" id="mainNavbar">
        <ul class="navbar-nav me-auto">
            <g:set var="navControllers"
                   value="${grailsApplication.controllerClasses.toList().sort { it.fullName }}"/>
            <g:if test="${navControllers}">
                <li class="nav-item dropdown">
                    <a class="nav-link dropdown-toggle" href="#" id="controllersDropdown" role="button"
                       data-bs-toggle="dropdown" aria-expanded="false">
                        <g:message code="welcome.artefact.controllers"/>
                    </a>
                    <ul class="dropdown-menu" aria-labelledby="controllersDropdown"
                        style="max-height: 60vh; overflow-y: auto;">
                        <g:each var="c" in="${navControllers}">
                            <g:set var="navControllerName" value="${(c.fullName ?: '')
                                    .tokenize('.')
                                    .last()
                                    .replaceFirst(/Controller$/, '')}"/>
                            <li>
                                <g:link controller="${c.logicalPropertyName}" namespace="${c.namespace}"
                                        class="dropdown-item">${((c.namespace ?: '').trim()) ? "${c.namespace} / ${navControllerName}" : navControllerName}</g:link>
                            </li>
                        </g:each>
                    </ul>
                </li>
            </g:if>

            <li class="nav-item dropdown">
                <a class="nav-link dropdown-toggle" href="#" id="statusDropdown" role="button"
                   data-bs-toggle="dropdown" aria-expanded="false">
                    <g:message code="layout.status"/>
                </a>
                <ul class="dropdown-menu" aria-labelledby="statusDropdown">
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.server"/>: ${request.servletContext.serverInfo}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.server.host"/>: ${InetAddress.localHost}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.app.environment"/>: ${Environment.current.name}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.app.profile"/>: ${grailsApplication.config.getProperty('grails.profile')}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.app.version"/>: <g:meta name="info.app.version"/></span></li>
                    <li><hr class="dropdown-divider"></li>
                    <li><span class="dropdown-item-text text-nowrap">Grails: <g:meta name="info.app.grailsVersion"/></span></li>
                    <li><span class="dropdown-item-text text-nowrap">Groovy: ${GroovySystem.getVersion()}</span></li>
                    <li><span class="dropdown-item-text text-nowrap">JVM: ${System.getProperty('java.version')}</span></li>
                    <li><span class="dropdown-item-text text-nowrap">Spring Boot: ${SpringBootVersion.getVersion()}</span></li>
                    <li><span class="dropdown-item-text text-nowrap">Spring: ${SpringVersion.getVersion()}</span></li>
                    <li><hr class="dropdown-divider"></li>
                    <li>
                        <span class="dropdown-item-text text-nowrap">
                            <g:if test="${Environment.reloadingAgentEnabled}"><g:message code="welcome.reloading.active"/></g:if>
                            <g:else><g:message code="welcome.reloading.inactive"/></g:else>
                        </span>
                    </li>
                </ul>
            </li>

            <li class="nav-item dropdown">
                <a class="nav-link dropdown-toggle" href="#" id="artefactsDropdown" role="button"
                   data-bs-toggle="dropdown" aria-expanded="false">
                    <g:message code="layout.artefacts"/>
                </a>
                <ul class="dropdown-menu" aria-labelledby="artefactsDropdown">
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.artefact.controllers"/>: ${grailsApplication.controllerClasses.size()}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.artefact.domains"/>: ${grailsApplication.domainClasses.size()}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.artefact.services"/>: ${grailsApplication.serviceClasses.size()}</span></li>
                    <li><span class="dropdown-item-text text-nowrap"><g:message code="welcome.artefact.taglibs"/>: ${grailsApplication.tagLibClasses.size()}</span></li>
                </ul>
            </li>

            <%-- Actuators: shown only when Spring Boot Actuator is present and exposes web endpoints --%>
            <g:set var="actuatorSupplierType"
                   value="${ClassUtils.isPresent('org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier', null) ? ClassUtils.forName('org.springframework.boot.actuate.endpoint.web.WebEndpointsSupplier', null) : null}"/>
            <g:set var="actuatorEndpoints"
                   value="${actuatorSupplierType && applicationContext.getBeanNamesForType(actuatorSupplierType) ? applicationContext.getBean(actuatorSupplierType).endpoints.toList().sort { it.endpointId.toString() } : []}"/>
            <g:if test="${actuatorEndpoints}">
                <g:set var="actuatorBasePath"
                       value="${grailsApplication.config.getProperty('management.endpoints.web.base-path') ?: '/actuator'}"/>
                <li class="nav-item dropdown">
                    <a class="nav-link dropdown-toggle" href="#" id="actuatorsDropdown" role="button"
                       data-bs-toggle="dropdown" aria-expanded="false">
                        <g:message code="layout.actuators"/>
                    </a>
                    <ul class="dropdown-menu" aria-labelledby="actuatorsDropdown"
                        style="max-height: 60vh; overflow-y: auto;">
                        <g:each var="endpoint" in="${actuatorEndpoints}">
                            <li><a class="dropdown-item" href="${request.contextPath}${actuatorBasePath}/${endpoint.rootPath}" target="_blank" rel="noopener">${endpoint.endpointId}</a></li>
                        </g:each>
                    </ul>
                </li>
            </g:if>

            <li class="nav-item dropdown">
                <a class="nav-link dropdown-toggle" href="#" id="pluginsDropdown" role="button"
                   data-bs-toggle="dropdown" aria-expanded="false">
                    <g:message code="welcome.plugins.title"/> (${applicationContext.getBean('pluginManager').allPlugins.size()})
                </a>
                <ul class="dropdown-menu p-2" id="nav-plugins-list" aria-labelledby="pluginsDropdown"
                    style="max-height: 60vh; overflow-y: auto; min-width: 16rem;">
                    <li class="pb-2">
                        <input type="search" class="form-control form-control-sm filter-input"
                               data-filter-list="#nav-plugins-list" data-filter-empty="#nav-plugins-empty"
                               placeholder="${message(code: 'welcome.filter.name')}"
                               aria-label="${message(code: 'welcome.filter.name')}">
                    </li>
                    <g:each var="navPlugin" in="${applicationContext.getBean('pluginManager').allPlugins.toList().sort { it.name.toLowerCase() }}">
                        <li data-name="${navPlugin.name}"><span class="dropdown-item-text text-nowrap">${navPlugin.name} - ${navPlugin.version}</span></li>
                    </g:each>
                    <li id="nav-plugins-empty" class="d-none"><span class="dropdown-item-text text-body-secondary"><g:message code="welcome.filter.none"/></span></li>
                </ul>
            </li>
        </ul>
        </div>
        <ul class="navbar-nav ms-auto">
            <g:set var="availableLocales" value="${application.getAttribute('availableLocales')}"/>
            <g:if test="${availableLocales && availableLocales.size() > 1}">
                <g:set var="currentLocale" value="${org.springframework.web.servlet.support.RequestContextUtils.getLocale(request)}"/>
                <li class="nav-item dropdown">
                    <a class="nav-link dropdown-toggle" href="#" id="localeDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false">
                        <i class="bi bi-globe me-1"></i>${currentLocale.getDisplayName(currentLocale)}
                    </a>
                    <ul class="dropdown-menu dropdown-menu-end" aria-labelledby="localeDropdown">
                        <%-- The default language stays pinned on top: a user who switched to a
                             language they cannot read must always find a recognizable way back. --%>
                        <g:set var="defaultLocale" value="${java.util.Locale.ENGLISH}"/>
                        <li>
                            <a class="dropdown-item${defaultLocale.language == currentLocale.language ? ' active' : ''}" href="?lang=${defaultLocale.toLanguageTag()}">${defaultLocale.getDisplayName(defaultLocale)}</a>
                        </li>
                        <li><hr class="dropdown-divider"></li>
                        <g:each in="${availableLocales}" var="availableLocale">
                            <g:if test="${availableLocale.language != defaultLocale.language}">
                                <li>
                                    <a class="dropdown-item${availableLocale.language == currentLocale.language ? ' active' : ''}" href="?lang=${availableLocale.toLanguageTag()}">${availableLocale.getDisplayName(availableLocale)}</a>
                                </li>
                            </g:if>
                        </g:each>
                    </ul>
                </li>
            </g:if>
            <li class="nav-item dropdown">
                <a class="nav-link dropdown-toggle d-flex align-items-center" href="#" id="themeDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false" aria-label="${message(code: 'layout.theme.toggle')}">
                    <i class="bi bi-circle-half theme-icon-active"></i>
                    <span class="d-lg-none ms-2"><g:message code="layout.theme.toggle"/></span>
                </a>
                <ul class="dropdown-menu dropdown-menu-end" aria-labelledby="themeDropdown">
                    <li>
                        <button type="button" class="dropdown-item d-flex align-items-center" data-bs-theme-value="light" aria-pressed="false">
                            <i class="bi bi-sun-fill me-2"></i><g:message code="layout.theme.light"/><i class="bi bi-check ms-auto d-none"></i>
                        </button>
                    </li>
                    <li>
                        <button type="button" class="dropdown-item d-flex align-items-center" data-bs-theme-value="dark" aria-pressed="false">
                            <i class="bi bi-moon-stars-fill me-2"></i><g:message code="layout.theme.dark"/><i class="bi bi-check ms-auto d-none"></i>
                        </button>
                    </li>
                    <li>
                        <button type="button" class="dropdown-item d-flex align-items-center" data-bs-theme-value="auto" aria-pressed="false">
                            <i class="bi bi-circle-half me-2"></i><g:message code="layout.theme.auto"/><i class="bi bi-check ms-auto d-none"></i>
                        </button>
                    </li>
                </ul>
            </li>
        </ul>
    </div>
</nav>

<div class="bg-body-tertiary">
    <div class="container-lg py-4">
        <g:flashMessages />
        <g:layoutBody/>
    </div>
</div>

<footer class="border-top py-5" role="contentinfo">
    <div class="container-lg">
        <div class="row g-4">
            <div class="col-12 col-md-4">
                <a class="card h-100 text-decoration-none shadow-sm border-1"
                   href="https://guides.grails.org" target="_blank" rel="noopener">
                    <div class="card-body p-4">
                        <div class="d-flex align-items-center justify-content-between mb-2">
                            <h6 class="card-title mb-0 fw-semibold"><g:message code="layout.guides.title"/></h6>
                            <asset:image src="advancedgrails.svg" alt="${message(code: 'layout.guides.title')}" width="34" height="34"/>
                        </div>
                        <p class="card-text text-body-secondary mb-0">
                            <g:message code="layout.guides.text"/>
                        </p>
                    </div>
                </a>
            </div>
            <div class="col-12 col-md-4">
                <a class="card h-100 text-decoration-none shadow-sm border-1"
                   href="https://grails.apache.org/docs/" target="_blank" rel="noopener">
                    <div class="card-body p-4">
                        <div class="d-flex align-items-center justify-content-between mb-2">
                            <h6 class="card-title mb-0 fw-semibold"><g:message code="layout.docs.title"/></h6>
                            <asset:image src="documentation.svg" alt="${message(code: 'layout.docs.title')}" width="34" height="34"/>
                        </div>
                        <p class="card-text text-body-secondary mb-0">
                            <g:message code="layout.docs.text"/>
                        </p>
                    </div>
                </a>
            </div>
            <div class="col-12 col-md-4">
                <a class="card h-100 text-decoration-none shadow-sm border-1"
                   href="https://grails.apache.org/community.html" target="_blank" rel="noopener">
                    <div class="card-body p-4">
                        <div class="d-flex align-items-center justify-content-between mb-2">
                            <h6 class="card-title mb-0 fw-semibold"><g:message code="layout.community.title"/></h6>
                            <asset:image src="community.svg" alt="${message(code: 'layout.community.title')}" width="34" height="34"/>
                        </div>
                        <p class="card-text text-body-secondary mb-0">
                            <g:message code="layout.community.text"/>
                        </p>
                    </div>
                </a>
            </div>
        </div>
    </div>
</footer>
<div id="spinner" class="position-absolute top-0 end-0 p-1" style="display:none;">
    <div class="spinner-border spinner-border-sm" role="status">
        <span class="visually-hidden"><g:message code="layout.loading"/></span>
    </div>
</div>
<asset:javascript src="application.js"/>
</body>
</html>
