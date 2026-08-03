# eDelivery/eFTI special components

These are required to fully implement eFTI Gate.

* [edelivery](edelivery) - fast and simple eDelivery AS4 messaging service, handles SOAP envelopes, eDelivery transport, and protocol translation between REST and eDelivery/SOAP.
* [multiplexer](multiplexer) - multiplexes queries to multiple remote gates, aggregates responses, and returns first result immediately, full results on retry.
* [xml-mapper](xml-mapper) - parses incoming eFTI XML requests (by default only new XSD schemas), extracts data into JSON for Ruuter/ReSql, and builds eFTI XML responses from JSON data returned by ReSql.
