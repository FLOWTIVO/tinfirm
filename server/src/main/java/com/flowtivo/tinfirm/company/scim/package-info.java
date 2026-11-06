/**
 * The company module's SCIM interface (ADR-0087): what Enterprise's SCIM directory and token issuance, in the
 * {@code ee} module, read and change of a company's members and its SCIM tokens, through the module's own checks; and
 * the ports {@link com.flowtivo.tinfirm.company.scim.ScimDirectory} and
 * {@link com.flowtivo.tinfirm.company.scim.ScimTokens} the core's endpoint and token API find when used, which answer
 * {@code 403 EDITION_REQUIRED} without {@code ee/}.
 */
@org.springframework.modulith.NamedInterface("scim")
package com.flowtivo.tinfirm.company.scim;
