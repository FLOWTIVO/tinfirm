package com.flowtivo.tinfirm.mcp.internal;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.OutlookManifest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;

/**
 * The manifest of Tinfirm's Outlook add-in (ADR-0074), a client of this module's endpoint: the add-in-only XML
 * manifest, which every Outlook that loads web add-ins takes, Mac included. One per company, since the task pane's URL
 * names the company whose endpoint it calls; its add-in id is a name-based UUID of the public URL and the company, so
 * that every download of it is the same add-in. Every member may download it; nothing in it is secret.
 * <p>
 * It asks for {@code ReadItem}, the least an add-in has, which reads the open email and opens Outlook's reply form;
 * its one button is on the read surface and opens a task pane the person may pin, which needs Mailbox 1.5.
 * <p>
 * The add-in is Pro's and Enterprise's (ADR-0077, decision 4, {@code OUTLOOK_ADDIN}): a member learns it after the
 * access check.
 */
@Component
class OutlookManifests implements OutlookAddIn {

    static final String FILE_NAME = "tinfirm-outlook-manifest.xml";

    /** The icons, files of the web app (`web/public/outlook/`). */
    static final int[] ICON_SIZES = { 16, 32, 64, 80, 128 };

    private final CompanyAccessGuard guard;

    private final TinfirmProperties tinfirm;

    private final Licensing licensing;

    OutlookManifests(CompanyAccessGuard guard, TinfirmProperties tinfirm, Licensing licensing) {
        this.guard = guard;
        this.tinfirm = tinfirm;
        this.licensing = licensing;
    }

    @Override
    @Transactional(readOnly = true)
    public OutlookManifest manifest(UUID companyId) {
        guard.require(companyId, CompanyPermission.VIEW_COMPANY);
        licensing.require(Feature.OUTLOOK_ADDIN);
        String base = tinfirm.publicBaseUrl();
        UUID addInId = addInId(base, companyId);
        String taskPane = taskPaneUrl(base, companyId);
        return new OutlookManifest(FILE_NAME, addInId, taskPane, xml(base, addInId, taskPane, companyId));
    }

    static UUID addInId(String base, UUID companyId) {
        return UUID.nameUUIDFromBytes(("tinfirm-outlook:" + base + ":" + companyId).getBytes(StandardCharsets.UTF_8));
    }

    /** The web app's route of the task pane (CONVENTIONS §11). */
    static String taskPaneUrl(String base, UUID companyId) {
        return base + "/outlook/" + companyId;
    }

    static String xml(String base, UUID addInId, String taskPane, UUID companyId) {
        String b = escape(base);
        String pane = escape(taskPane);
        String icon = b + "/outlook/icon-";
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <OfficeApp xmlns="http://schemas.microsoft.com/office/appforoffice/1.1"
                    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                    xmlns:bt="http://schemas.microsoft.com/office/officeappbasictypes/1.0"
                    xmlns:mailappor="http://schemas.microsoft.com/office/mailappversionoverrides/1.0"
                    xsi:type="MailApp">
                  <Id>%1$s</Id>
                  <Version>1.0.0.0</Version>
                  <ProviderName>Tinfirm</ProviderName>
                  <DefaultLocale>en-US</DefaultLocale>
                  <DisplayName DefaultValue="Tinfirm"/>
                  <Description DefaultValue="Ask your company's agents to summarise, triage or investigate the open email, or to draft a reply."/>
                  <IconUrl DefaultValue="%2$s64.png"/>
                  <HighResolutionIconUrl DefaultValue="%2$s128.png"/>
                  <SupportUrl DefaultValue="%3$s/c/%4$s/inbox"/>
                  <AppDomains>
                    <AppDomain>%3$s</AppDomain>
                  </AppDomains>
                  <Hosts>
                    <Host Name="Mailbox"/>
                  </Hosts>
                  <Requirements>
                    <Sets>
                      <Set Name="Mailbox" MinVersion="1.1"/>
                    </Sets>
                  </Requirements>
                  <FormSettings>
                    <Form xsi:type="ItemRead">
                      <DesktopSettings>
                        <SourceLocation DefaultValue="%5$s"/>
                        <RequestedHeight>450</RequestedHeight>
                      </DesktopSettings>
                    </Form>
                  </FormSettings>
                  <Permissions>ReadItem</Permissions>
                  <Rule xsi:type="RuleCollection" Mode="Or">
                    <Rule xsi:type="ItemIs" ItemType="Message" FormType="Read"/>
                  </Rule>
                  <DisableEntityHighlighting>false</DisableEntityHighlighting>
                  <VersionOverrides xmlns="http://schemas.microsoft.com/office/mailappversionoverrides" xsi:type="VersionOverridesV1_0">
                    <VersionOverrides xmlns="http://schemas.microsoft.com/office/mailappversionoverrides/1.1" xsi:type="VersionOverridesV1_1">
                      <Requirements>
                        <bt:Sets DefaultMinVersion="1.5">
                          <bt:Set Name="Mailbox"/>
                        </bt:Sets>
                      </Requirements>
                      <Hosts>
                        <Host xsi:type="MailHost">
                          <DesktopFormFactor>
                            <FunctionFile resid="TaskPane.Url"/>
                            <ExtensionPoint xsi:type="MessageReadCommandSurface">
                              <OfficeTab id="TabDefault">
                                <Group id="tinfirmReadGroup">
                                  <Label resid="Group.Label"/>
                                  <Control xsi:type="Button" id="tinfirmOpenPane">
                                    <Label resid="Button.Label"/>
                                    <Supertip>
                                      <Title resid="Button.Label"/>
                                      <Description resid="Button.Tooltip"/>
                                    </Supertip>
                                    <Icon>
                                      <bt:Image size="16" resid="Icon.16"/>
                                      <bt:Image size="32" resid="Icon.32"/>
                                      <bt:Image size="80" resid="Icon.80"/>
                                    </Icon>
                                    <Action xsi:type="ShowTaskpane">
                                      <SourceLocation resid="TaskPane.Url"/>
                                      <SupportsPinning>true</SupportsPinning>
                                    </Action>
                                  </Control>
                                </Group>
                              </OfficeTab>
                            </ExtensionPoint>
                          </DesktopFormFactor>
                        </Host>
                      </Hosts>
                      <Resources>
                        <bt:Images>
                          <bt:Image id="Icon.16" DefaultValue="%2$s16.png"/>
                          <bt:Image id="Icon.32" DefaultValue="%2$s32.png"/>
                          <bt:Image id="Icon.80" DefaultValue="%2$s80.png"/>
                        </bt:Images>
                        <bt:Urls>
                          <bt:Url id="TaskPane.Url" DefaultValue="%5$s"/>
                        </bt:Urls>
                        <bt:ShortStrings>
                          <bt:String id="Group.Label" DefaultValue="Tinfirm"/>
                          <bt:String id="Button.Label" DefaultValue="Ask an agent"/>
                        </bt:ShortStrings>
                        <bt:LongStrings>
                          <bt:String id="Button.Tooltip" DefaultValue="Summarise, triage or investigate this email with one of your company's agents, or have it draft a reply."/>
                        </bt:LongStrings>
                      </Resources>
                    </VersionOverrides>
                  </VersionOverrides>
                </OfficeApp>
                """.formatted(addInId, icon, b, companyId, pane);
    }

    /** The five characters XML gives a meaning, in an attribute's or an element's text. */
    static String escape(String text) {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }
}
