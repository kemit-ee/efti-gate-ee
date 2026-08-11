import type {UIL} from 'src/api/types'

export function combineUIL(uil: UIL) {
  return [uil.gateId, uil.platformId, uil.datasetId].map(encodeURIComponent).join('/')
}