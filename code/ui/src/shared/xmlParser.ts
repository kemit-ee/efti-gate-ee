export function parseXmlToJson(xmlString: string) {
  if (!xmlString) return null
  try {
    const parser = new DOMParser()
    const xmlDoc = parser.parseFromString(xmlString, 'text/xml')
    if (xmlDoc.getElementsByTagName('parsererror').length > 0) {
      console.error('XML parsing error')
      return null
    }
    return nodeToJson(xmlDoc.documentElement)
  } catch (e) {
    console.error('XML to JSON conversion failed', e)
    return null
  }
}

function nodeToJson(node: Node): any {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.nodeValue?.trim() || ''
  }

  if (node.nodeType !== Node.ELEMENT_NODE) {
    return null
  }

  const element = node as Element
  const json: any = {}

  // Handle attributes
  for (let i = 0; i < element.attributes.length; i++) {
    const attr = element.attributes[i]
    if (attr.name !== 'xmlns' && !attr.name.startsWith('xmlns:')) {
      json[attr.localName] = attr.value
    }
  }

  // Handle children
  const children = element.childNodes
  let hasElements = false
  for (let i = 0; i < children.length; i++) {
    const child = children[i]
    if (child.nodeType === Node.ELEMENT_NODE) {
      hasElements = true
      const childName = (child as Element).localName
      const childJson = nodeToJson(child)

      if (json[childName]) {
        if (!Array.isArray(json[childName])) {
          json[childName] = [json[childName]]
        }
        json[childName].push(childJson)
      } else {
        json[childName] = childJson
      }
    } else if (child.nodeType === Node.TEXT_NODE) {
      const text = child.nodeValue?.trim()
      if (text) {
        if (hasElements || Object.keys(json).length > 0) {
          json.value = text
        } else {
          return text
        }
      }
    }
  }

  return json
}
