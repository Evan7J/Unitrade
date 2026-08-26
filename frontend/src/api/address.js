import request from '../utils/request'
export const getList = () => request.get('/address/list')
export const save = (d) => request.post('/address/save', d)
export const update = (d) => request.put('/address/update', d)
export const del = (id) => request.delete('/address/delete/' + id)