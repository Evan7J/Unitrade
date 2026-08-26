import request from '../utils/request'
export const save = (d) => request.post('/review/save', d)
export const getUserReviews = (uid) => request.get('/review/user/' + uid)
export const checkReviewed = (oid) => request.get('/review/check/' + oid)
