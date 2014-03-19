package org.intellij.sonar.sonarserver;

import com.intellij.codeInspection.ProblemHighlightType;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.project.Project;
import org.apache.commons.lang.StringUtils;
import org.intellij.sonar.SonarIssuesProvider;
import org.intellij.sonar.SonarRulesProvider;
import org.intellij.sonar.SonarSettingsBean;
import org.intellij.sonar.SonarSeverity;
import org.intellij.sonar.SyncWithSonarResult;
import org.intellij.sonar.persistence.SonarServerConfigurationBean;
import org.intellij.sonar.util.GuaveStreamUtil;
import org.intellij.sonar.util.ThrowableUtils;
import org.jetbrains.annotations.NotNull;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;
import org.sonar.wsclient.services.Rule;
import org.sonar.wsclient.services.RuleQuery;
import org.sonar.wsclient.services.Violation;
import org.sonar.wsclient.services.ViolationQuery;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class SonarServer {

  private static final String VERSION_URL = "/api/server/version";
  private static final int CONNECT_TIMEOUT_IN_MILLISECONDS = 3000;
  private static final int READ_TIMEOUT_IN_MILLISECONDS = 6000;
  private static final String USER_AGENT = "SonarQube Community Plugin";

  public SonarServer() {
  }

  public static SonarServer getInstance() {
    return ServiceManager.getService(SonarServer.class);
  }

  public Sonar createSonar(SonarServerConfigurationBean configurationBean) {
    Sonar sonar;
    if (configurationBean.anonymous) {
      sonar = createSonar(configurationBean.hostUrl, null, null);
    } else {
      configurationBean.loadPassword();
      sonar = createSonar(configurationBean.hostUrl, configurationBean.user, configurationBean.password);
      configurationBean.password = null;
    }
    return sonar;
  }

  public String verifySonarConnection(SonarSettingsBean sonarSettingsBean) throws SonarServerConnectionException {
    HttpURLConnection httpURLConnection = getHttpConnection(sonarSettingsBean.host);

    try {
      int statusCode = httpURLConnection.getResponseCode();
      if (statusCode != HttpURLConnection.HTTP_OK) {
        throw new SonarServerConnectionException("ResponseCode: %d Url: %s", statusCode, httpURLConnection.getURL());
      }
      return GuaveStreamUtil.toString(httpURLConnection.getInputStream());
    } catch (IOException e) {
      throw new SonarServerConnectionException("Cannot read data from url: %s\n\n Cause: \n%s", httpURLConnection.getURL(), ThrowableUtils.getPrettyStackTraceAsString(e));
    }
  }

  private HttpURLConnection getHttpConnection(String hostName) throws SonarServerConnectionException {
    URL sonarServerUrl = null;
    try {
      sonarServerUrl = new URL(getHostSafe(hostName) + VERSION_URL);

      HttpURLConnection connection = (HttpURLConnection) sonarServerUrl.openConnection();
      connection.setConnectTimeout(CONNECT_TIMEOUT_IN_MILLISECONDS);
      connection.setReadTimeout(READ_TIMEOUT_IN_MILLISECONDS);
      connection.setInstanceFollowRedirects(true);
      connection.setRequestProperty("User-Agent", USER_AGENT);
      return connection;
    } catch (MalformedURLException e) {
      throw new SonarServerConnectionException("Invalid url: %s", e, hostName);
    } catch (IOException e) {
      throw new SonarServerConnectionException("Couldn't connect to url: %s", e, sonarServerUrl.toString());
    }
  }

  private String getHostSafe(String hostName) {
    return StringUtils.removeEnd(hostName, "/");
  }

  @NotNull
  public List<Violation> getViolations(SonarSettingsBean sonarSettingsBean) {
    if (null == sonarSettingsBean) {
      return Collections.emptyList();
    }
    final Sonar sonar = createSonar(sonarSettingsBean);
    ViolationQuery violationQuery = ViolationQuery
        .createForResource(sonarSettingsBean.resource)
        .setDepth(-1)
        .setSeverities("BLOCKER", "CRITICAL", "MAJOR", "MINOR", "INFO");

    return sonar.findAll(violationQuery);
  }

  public Sonar createSonar(String host, String user, String password) {
    host = getHostSafe(host);
    return StringUtils.isEmpty(user) ? Sonar.create(host) : Sonar.create(host, user, password);
  }

  public Sonar createSonar(SonarSettingsBean sonarSettingsBean) {
    return createSonar(sonarSettingsBean.host, sonarSettingsBean.user, sonarSettingsBean.password);
  }

  public Collection<Rule> getAllRules(Collection<SonarSettingsBean> sonarSettingsBeans, @NotNull ProgressIndicator indicator) {
    List<Rule> rulesResult = new LinkedList<Rule>();
    Set<String> ruleKeys = new LinkedHashSet<String>();
    for (SonarSettingsBean sonarSettingsBean : sonarSettingsBeans) {
      indicator.checkCanceled();

      final Sonar sonar = createSonar(sonarSettingsBean);

      // for all SettingsBeans do:  find language
      String resourceUrl = sonarSettingsBean.resource;
      if (StringUtils.isNotBlank(resourceUrl)) {
        ResourceQuery query = ResourceQuery.createForMetrics(resourceUrl, "language");
        List<Resource> resources = sonar.findAll(query);
        if (null != resources && !resources.isEmpty()) {
          for (Resource resource : resources) {
            indicator.checkCanceled();

            // find rule
            String language = resource.getLanguage();
            if (StringUtils.isNotBlank(language)) {
              RuleQuery ruleQuery = new RuleQuery(language);
              List<Rule> rules = sonar.findAll(ruleQuery);
              if (null != rules) {
                for (Rule rule : rules) {
                  indicator.checkCanceled();

                  if (!ruleKeys.contains(rule.getKey())) {
                    ruleKeys.add(rule.getKey());
                    rulesResult.add(rule);
                  }
                }
              }
            }
          }
        }
      }
    }
    // return all collected rules
    return rulesResult;
  }

  public SyncWithSonarResult sync(Project project, @NotNull ProgressIndicator indicator) {
    SyncWithSonarResult syncWithSonarResult = new SyncWithSonarResult();
    SonarIssuesProvider sonarIssuesProvider = ServiceManager.getService(project,
        SonarIssuesProvider.class);
    if (null != sonarIssuesProvider) {
      syncWithSonarResult.violationsCount = sonarIssuesProvider.syncWithSonar(project, indicator);
    }
    SonarRulesProvider sonarRulesProvider = ServiceManager.getService(project, SonarRulesProvider.class);
    if (null != sonarRulesProvider) {
      syncWithSonarResult.rulesCount = sonarRulesProvider.syncWithSonar(project, indicator);
    }

    return syncWithSonarResult;
  }

  public ProblemHighlightType sonarSeverityToProblemHighlightType(String sonarSeverity) {
    if (StringUtils.isBlank(sonarSeverity)) {
      return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
    } else {
      sonarSeverity = sonarSeverity.toUpperCase();
      if (SonarSeverity.BLOCKER.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.ERROR;
      } else if (SonarSeverity.CRITICAL.toString().equals(sonarSeverity) || SonarSeverity.MAJOR.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
      } else if (SonarSeverity.INFO.toString().equals(sonarSeverity) || SonarSeverity.MINOR.toString().equals(sonarSeverity)) {
        return ProblemHighlightType.WEAK_WARNING;
      } else {
        return ProblemHighlightType.GENERIC_ERROR_OR_WARNING;
      }
    }
  }

  public List<Resource> getAllProjectsAndModules(Sonar sonar) {
    List<Resource> allResources = new LinkedList<Resource>();
    List<Resource> projects = getAllProjects(sonar);
    if (null != projects) {
      for (Resource project : projects) {
        allResources.add(project);
        List<Resource> modules = getAllModules(sonar, project.getId());
        if (null != modules) {
          for (Resource module : modules) {
            allResources.add(module);
          }
        }
      }
    }
    return allResources;
  }

  public List<Resource> getAllProjects(Sonar sonar) {
    ResourceQuery projectResourceQuery = new ResourceQuery();
    projectResourceQuery.setQualifiers(Resource.QUALIFIER_PROJECT);
    return sonar.findAll(projectResourceQuery);
  }

  public List<Resource> getAllModules(Sonar sonar, Integer projectResourceId) {
    ResourceQuery moduleResourceQuery = new ResourceQuery(projectResourceId);
    moduleResourceQuery.setDepth(-1);
    moduleResourceQuery.setQualifiers(Resource.QUALIFIER_MODULE);
    return sonar.findAll(moduleResourceQuery);
  }

}
