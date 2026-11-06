# Public Suffix List

`public_suffix_list.dat` is the Public Suffix List (https://publicsuffix.org), including its
private section (shared hosting such as `github.io`), under the Mozilla Public License 2.0 (the
terms are at the top of the file). Copy taken from the `publicsuffix-list` package of
2025-12-28.

The server reads only this copy: the `agent` module's `PublicSuffixes` parses it once, and
`NetworkHosts` refuses with it a wildcard network host over a public suffix (`*.com.sg`,
`*.github.io`) when an agent names one (CONVENTIONS §15.5). The run module pushes the same copy
to runner hosts of protocol v2 (`PublicSuffixPush`, through `agent.PublicSuffixList`): a host
whose `hello` reports another version of the list, or none, gets it in `psl.update` right after
`hello`, so that it refuses the same wildcards (CONVENTIONS §15.7). Nothing downloads, stores or
refreshes the list, and the `TINFIRM_PUBLIC_SUFFIX_*` variables are not read. A newer list
reaches the server, and from it the runner hosts, only by renewing this file in a release:

    curl -fsSL https://publicsuffix.org/list/public_suffix_list.dat -o public_suffix_list.dat

Then update the date above and in CONVENTIONS §15.5.
